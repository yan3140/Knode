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
import jakarta.annotation.PreDestroy;
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
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "knode.search.consumer.stream", name = "enabled", havingValue = "true")
public class LettuceContentStreamClient implements ContentStreamClient, AutoCloseable {
    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final RedisURI redisUri;
    private final RedisClient redisClient;
    private final Object connectionLock = new Object();
    private StatefulRedisConnection<String, String> connection;

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
        List<PendingMessage> pending = commands.xpending(
                streamKey,
                groupName,
                Range.unbounded(),
                Limit.from(boundedBatch)
        );
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        List<String> eligibleIds = new ArrayList<>();
        for (PendingMessage item : pending) {
            if (item != null && item.getId() != null && item.getMsSinceLastDelivery() >= safeMinIdleMs) {
                eligibleIds.add(item.getId());
                if (eligibleIds.size() >= boundedBatch) {
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
