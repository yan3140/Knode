package com.knode.search.consumer.stream;

import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.config.SearchConsumerRuntimeConfig;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.PendingMessage;
import io.lettuce.core.models.stream.PendingMessages;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "knode.search.consumer.stream", name = "enabled", havingValue = "true")
public class LettuceContentStreamClient implements ContentStreamClient, AutoCloseable {
    private static final int MAX_PENDING_SCAN_PAGES = 4;

    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final RedisURI redisUri;
    private final RedisClient redisClient;
    private final Object connectionLock = new Object();
    private final Object pendingCursorLock = new Object();
    private StatefulRedisConnection<String, String> connection;
    private String pendingCursor = "-";

    @Autowired
    public LettuceContentStreamClient(
            SearchConsumerProperties properties,
            @Value("${knode.environment:local}") String environment) {
        this(
                streamKeyForEnvironment(environment),
                properties.getStream().getGroup().trim(),
                resolveConsumerName(properties.getStream().getConsumerName()),
                properties.getStream().getRedis()
        );
    }

    public LettuceContentStreamClient(
            String streamKey,
            String groupName,
            String consumerName,
            SearchConsumerProperties.RedisProps redisProps) {
        if (!StringUtils.hasText(redisProps.getHost()) || redisProps.getPort() < 1 || redisProps.getPort() > 65535) {
            throw new IllegalStateException("Invalid knode.search.consumer.stream.redis host/port configuration");
        }
        this.streamKey = streamKey;
        this.groupName = groupName;
        this.consumerName = consumerName;

        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisProps.getHost().trim())
                .withPort(redisProps.getPort())
                .withDatabase(Math.max(0, redisProps.getDatabase()))
                .withTimeout(Duration.ofMillis(Math.max(100L, redisProps.getCommandTimeoutMs())));
        if (StringUtils.hasText(redisProps.getUsername()) && StringUtils.hasText(redisProps.getPassword())) {
            uriBuilder.withAuthentication(redisProps.getUsername(), redisProps.getPassword().toCharArray());
        } else if (StringUtils.hasText(redisProps.getPassword())) {
            uriBuilder.withPassword(redisProps.getPassword().toCharArray());
        }
        if (redisProps.isTls()) {
            uriBuilder.withSsl(true);
        }
        this.redisUri = uriBuilder.build();

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(Math.max(100L, redisProps.getConnectTimeoutMs())))
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();
        this.redisClient = RedisClient.create(this.redisUri);
        this.redisClient.setOptions(clientOptions);
    }

    public static String streamKeyForEnvironment(String rawEnvironment) {
        return "knode:" + SearchConsumerRuntimeConfig.sanitizeEnvironment(rawEnvironment) + ":v1:events:content";
    }

    @Override
    public String streamKey() {
        return streamKey;
    }

    @Override
    public String groupName() {
        return groupName;
    }

    @Override
    public String consumerName() {
        return consumerName;
    }

    @Override
    public List<StreamRecord> claimIdlePending(int batchSize, long minIdleMs) {
        int boundedBatch = Math.max(1, batchSize);
        long safeMinIdleMs = Math.max(0L, minIdleMs);
        RedisCommands<String, String> commands = commands();

        List<String> eligibleIds = new ArrayList<>();
        synchronized (pendingCursorLock) {
            boolean wrappedToHead = false;
            for (int page = 0; page < MAX_PENDING_SCAN_PAGES && eligibleIds.size() < boundedBatch; page++) {
                String startCursor = this.pendingCursor;
                Range<String> range = "-".equals(startCursor)
                        ? Range.unbounded()
                        : Range.from(Range.Boundary.including(startCursor), Range.Boundary.unbounded());
                List<PendingMessage> pending = commands.xpending(
                        streamKey,
                        groupName,
                        range,
                        Limit.from(boundedBatch)
                );
                if (pending == null || pending.isEmpty()) {
                    this.pendingCursor = "-";
                    if (!"-".equals(startCursor) && !wrappedToHead) {
                        wrappedToHead = true;
                        continue;
                    }
                    break;
                }
                for (PendingMessage item : pending) {
                    if (item != null && item.getId() != null && item.getMsSinceLastDelivery() >= safeMinIdleMs) {
                        eligibleIds.add(item.getId());
                        if (eligibleIds.size() >= boundedBatch) {
                            break;
                        }
                    }
                }
                if (pending.size() < boundedBatch) {
                    // Reached tail of PEL; wrap cursor back to head.
                    this.pendingCursor = "-";
                    if (eligibleIds.isEmpty() && !"-".equals(startCursor) && !wrappedToHead) {
                        wrappedToHead = true;
                        continue;
                    }
                    break;
                }
                String lastId = pending.get(pending.size() - 1).getId();
                this.pendingCursor = nextStreamId(lastId);
                if (!eligibleIds.isEmpty()) {
                    break;
                }
            }
        }
        if (eligibleIds.isEmpty()) {
            return List.of();
        }
        List<StreamMessage<String, String>> claimed = commands.xclaim(
                streamKey,
                Consumer.from(groupName, consumerName),
                safeMinIdleMs,
                eligibleIds.toArray(String[]::new)
        );
        return toStreamRecords(claimed);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<StreamRecord> readNew(int batchSize, long blockMs) {
        int boundedBatch = Math.max(1, batchSize);
        long boundedBlock = Math.max(1L, blockMs);
        RedisCommands<String, String> commands = commands();
        List<StreamMessage<String, String>> messages = commands.xreadgroup(
                Consumer.from(groupName, consumerName),
                XReadArgs.Builder.count(boundedBatch).block(boundedBlock),
                XReadArgs.StreamOffset.lastConsumed(streamKey)
        );
        return toStreamRecords(messages);
    }

    @Override
    public long ack(List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return 0L;
        }
        Long acked = commands().xack(streamKey, groupName, recordIds.toArray(String[]::new));
        return acked == null ? 0L : acked;
    }

    @Override
    public OptionalLong queryPendingCount() {
        PendingMessages summary = commands().xpending(streamKey, groupName);
        if (summary == null) {
            return OptionalLong.of(0L);
        }
        return OptionalLong.of(Math.max(0L, summary.getCount()));
    }

    static String nextStreamId(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return "-";
        }
        int dash = streamId.indexOf('-');
        if (dash <= 0 || dash >= streamId.length() - 1) {
            return "-";
        }
        try {
            long ms = Long.parseUnsignedLong(streamId.substring(0, dash));
            long seq = Long.parseUnsignedLong(streamId.substring(dash + 1));
            if (seq == -1L) {
                if (ms == -1L) {
                    return "-";
                }
                return Long.toUnsignedString(ms + 1L) + "-0";
            }
            return Long.toUnsignedString(ms) + "-" + Long.toUnsignedString(seq + 1L);
        } catch (NumberFormatException ignored) {
            return "-";
        }
    }

    private RedisCommands<String, String> commands() {
        synchronized (connectionLock) {
            if (connection == null || !connection.isOpen()) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignored) {
                        // Best-effort cleanup before reopening.
                    }
                }
                connection = redisClient.connect(redisUri);
            }
            return connection.sync();
        }
    }

    private static List<StreamRecord> toStreamRecords(List<StreamMessage<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<StreamRecord> records = new ArrayList<>(messages.size());
        for (StreamMessage<String, String> message : messages) {
            if (message == null || message.getId() == null) {
                continue;
            }
            Map<String, String> copy = new LinkedHashMap<>();
            if (message.getBody() != null) {
                copy.putAll(message.getBody());
            }
            records.add(new StreamRecord(message.getId(), copy));
        }
        return records;
    }

    private static String resolveConsumerName(String configured) {
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        String host = "instance";
        try {
            host = InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9._-]", "-");
        } catch (Exception ignored) {
            // Fall back to default host token.
        }
        return "knode-search-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (connectionLock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // Ignore shutdown errors.
                }
                connection = null;
            }
        }
        redisClient.shutdown();
    }
}
