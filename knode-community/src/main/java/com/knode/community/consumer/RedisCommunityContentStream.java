package com.knode.community.consumer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisCommunityContentStream implements CommunityContentStream {
    private static final Logger LOG = LoggerFactory.getLogger(RedisCommunityContentStream.class);
    public static final String GROUP_NAME = "knode-community-v1";

    private final StringRedisTemplate redis;
    private final LettuceConnectionFactory factory;
    private final ContentEventIngestor ingestor;
    private final byte[] streamKey;
    private final String consumerName;
    private final int batchSize;
    private final long claimIdleMs;
    private volatile boolean groupEnsured = false;

    public RedisCommunityContentStream(
            StringRedisTemplate redis,
            LettuceConnectionFactory factory,
            ContentEventIngestor ingestor,
            String streamKey,
            int batchSize,
            long claimIdleMs) {
        this.redis = redis;
        this.factory = factory;
        this.ingestor = ingestor;
        this.streamKey = streamKey.getBytes(StandardCharsets.UTF_8);
        this.consumerName = "knode-community-" + UUID.randomUUID();
        this.batchSize = batchSize;
        this.claimIdleMs = claimIdleMs;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public int pollAndIngestOnce() {
        ensureConsumerGroup();
        int handled = 0;
        try {
            handled += reclaimAndProcessIdlePending();
            handled += readAndProcessNewMessages();
        } catch (RedisSystemException failure) {
            if (containsText(failure, "NOGROUP")) {
                groupEnsured = false;
                ensureConsumerGroup();
            } else {
                throw failure;
            }
        }
        return handled;
    }

    private void ensureConsumerGroup() {
        if (groupEnsured) {
            return;
        }
        try {
            redis.execute((RedisCallback<Void>) conn -> {
                conn.streamCommands().xGroupCreate(streamKey, GROUP_NAME, ReadOffset.from("0-0"), true);
                return null;
            });
            groupEnsured = true;
        } catch (RuntimeException failure) {
            if (containsText(failure, "BUSYGROUP")) {
                groupEnsured = true;
                return;
            }
            throw failure;
        }
    }

    private int reclaimAndProcessIdlePending() {
        Map<String, Integer> deliveryCounts = new HashMap<>();
        List<ByteRecord> claimed = redis.execute((RedisCallback<List<ByteRecord>>) conn -> {
            PendingMessages pending = conn.streamCommands().xPending(
                    streamKey,
                    GROUP_NAME,
                    Range.unbounded(),
                    (long) batchSize);
            if (pending == null || pending.isEmpty()) {
                return List.of();
            }
            List<RecordId> idleIds = new ArrayList<>();
            for (PendingMessage msg : pending) {
                if (msg.getElapsedTimeSinceLastDelivery().toMillis() >= claimIdleMs) {
                    idleIds.add(msg.getId());
                    deliveryCounts.put(msg.getIdAsString(), (int) Math.min(Integer.MAX_VALUE, msg.getTotalDeliveryCount() + 1));
                }
            }
            if (idleIds.isEmpty()) {
                return List.of();
            }
            List<ByteRecord> res = conn.streamCommands().xClaim(
                    streamKey,
                    GROUP_NAME,
                    consumerName,
                    Duration.ofMillis(claimIdleMs),
                    idleIds.toArray(RecordId[]::new));
            return res == null ? List.of() : res;
        });
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (ByteRecord record : claimed) {
            int attempt = deliveryCounts.getOrDefault(record.getId().getValue(), 2);
            if (processSingleRecord(record, attempt)) {
                processed++;
            }
        }
        return processed;
    }

    private int readAndProcessNewMessages() {
        List<ByteRecord> records = redis.execute((RedisCallback<List<ByteRecord>>) conn ->
                conn.streamCommands().xReadGroup(
                        Consumer.from(GROUP_NAME, consumerName),
                        StreamReadOptions.empty().count(batchSize),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed())));
        if (records == null || records.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (ByteRecord record : records) {
            if (processSingleRecord(record, 1)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean processSingleRecord(ByteRecord record, int attempt) {
        String entryId = record.getId().getValue();
        String rawJson = extractExactEventField(record.getValue());
        try {
            ContentEventIngestor.IngestResult result = ingestor.ingest(entryId, rawJson, attempt);
            if (result.ackStream()) {
                acknowledge(record.getId());
            }
            return true;
        } catch (RuntimeException dbOrTransientFailure) {
            // Never ACK when local database intake failed; entry remains in PEL for retry.
            LOG.warn("event=community_stream_ingest_deferred entryId={} attempt={} errorType={}",
                    entryId, attempt, dbOrTransientFailure.getClass().getSimpleName());
            return false;
        }
    }

    private void acknowledge(RecordId id) {
        redis.execute((RedisCallback<Long>) conn -> conn.streamCommands().xAck(streamKey, GROUP_NAME, id));
    }

    private static String extractExactEventField(Map<byte[], byte[]> map) {
        if (map == null || map.size() != 1) {
            return null;
        }
        Map.Entry<byte[], byte[]> only = map.entrySet().iterator().next();
        String fieldName = new String(only.getKey(), StandardCharsets.UTF_8);
        if (!"event".equals(fieldName) || only.getValue() == null) {
            return null;
        }
        return new String(only.getValue(), StandardCharsets.UTF_8);
    }

    private static boolean containsText(Throwable error, String needle) {
        Throwable cur = error;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains(needle)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    @Override
    public void close() {
        factory.destroy();
    }
}
