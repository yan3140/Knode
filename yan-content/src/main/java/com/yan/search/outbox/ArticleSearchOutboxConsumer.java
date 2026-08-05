package com.yan.search.outbox;

import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.reliabletask.ReliableTaskStatus;
import com.yan.reliabletask.redisstream.ReliableTaskRedisStreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnBean(ArticleSearchOutboxMessageGateway.class)
@ConditionalOnExpression("'${blog.reliable-task.redis-stream.enabled:false}' == 'true' and '${blog.search.outbox.stream.enabled:false}' == 'true'")
public class ArticleSearchOutboxConsumer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleSearchOutboxConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final ReliableTaskRedisStreamProperties properties;
    private final ArticleSearchOutboxMapper outboxMapper;
    private final ArticleSearchOutboxProcessor processor;
    private final int leaseSeconds;
    private final int batchSize;
    private final int consumerCount;

    public ArticleSearchOutboxConsumer(StringRedisTemplate redisTemplate,
                                       ReliableTaskRedisStreamProperties properties,
                                       ArticleSearchOutboxMapper outboxMapper,
                                       ArticleSearchOutboxProcessor processor,
                                       @Value("${blog.search.outbox.lease-seconds:30}") int leaseSeconds,
                                       @Value("${blog.search.outbox.stream.consumer-batch-size:20}") int batchSize,
                                       @Value("${blog.search.outbox.stream.consumer-count:2}") int consumerCount) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.outboxMapper = outboxMapper;
        this.processor = processor;
        this.leaseSeconds = leaseSeconds;
        this.batchSize = batchSize;
        this.consumerCount = Math.max(1, consumerCount);
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureGroup();
    }

    @Scheduled(fixedDelayString = "${blog.search.outbox.stream.poll-delay-ms:200}")
    public void consume() {
        outboxMapper.recoverExpiredClaims();
        for (int i = 0; i < consumerCount; i++) {
            consumeAs(properties.getConsumers().getArticleSearch() + "-" + i);
        }
    }

    private void consumeAs(String consumerName) {
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redisTemplate.opsForStream().read(
                    Consumer.from(properties.getGroups().getArticleSearch(), consumerName),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                            .count(batchSize)
                            .block(Duration.ofMillis(100)),
                    StreamOffset.create(properties.getStreams().getArticleSearch(), ReadOffset.lastConsumed()));
        } catch (RedisSystemException readFailure) {
            if (isNoGroup(readFailure)) {
                ensureGroup();
                return;
            }
            throw readFailure;
        }
        if (records == null || records.isEmpty()) {
            return;
        }
        List<ArticleSearchOutboxEvent> claimed = new ArrayList<>(records.size());
        List<MapRecord<String, Object, Object>> claimedRecords = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            ArticleSearchOutboxEvent event = claim(record);
            if (event != null) {
                claimed.add(event);
                claimedRecords.add(record);
            }
        }
        if (claimed.isEmpty()) {
            return;
        }
        processor.process(claimed);
        claimedRecords.forEach(this::ack);
    }

    private ArticleSearchOutboxEvent claim(MapRecord<String, Object, Object> record) {
        Long outboxId = parseOutboxId(record.getValue());
        if (outboxId == null) {
            ack(record);
            return null;
        }
        ArticleSearchOutboxEvent event = outboxMapper.selectById(outboxId);
        if (event == null) {
            LOGGER.warn("event=article_search_stream_message_orphaned outboxId={}", outboxId);
            ack(record);
            return null;
        }
        if (ReliableTaskStatus.PENDING.equals(event.getStatus())) {
            if (outboxMapper.claim(event.getId(), leaseSeconds) != 1) {
                LOGGER.info("event=article_search_stream_message_claim_skipped outboxId={}", event.getId());
                ack(record);
                return null;
            }
            event = outboxMapper.selectById(outboxId);
        }
        if (!ReliableTaskStatus.PROCESSING.equals(event.getStatus())) {
            LOGGER.info("event=article_search_stream_message_ignored outboxId={} status={}",
                    event.getId(), event.getStatus());
            ack(record);
            return null;
        }
        return event;
    }

    private void ensureGroup() {
        String stream = properties.getStreams().getArticleSearch();
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(stream))) {
            redisTemplate.opsForStream().add(org.springframework.data.redis.connection.stream.StreamRecords
                    .mapBacked(Map.of("_init", "1"))
                    .withStreamKey(stream));
        }
        try {
            redisTemplate.opsForStream().createGroup(
                    stream,
                    ReadOffset.latest(),
                    properties.getGroups().getArticleSearch());
        } catch (RedisSystemException createFailure) {
            if (isBusyGroup(createFailure)) {
                return;
            }
            throw createFailure;
        }
    }

    private boolean isBusyGroup(RedisSystemException failure) {
        return containsMessage(failure, "BUSYGROUP");
    }

    private boolean isNoGroup(RedisSystemException failure) {
        return containsMessage(failure, "NOGROUP");
    }

    private boolean containsMessage(Throwable failure, String text) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void ack(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(
                properties.getStreams().getArticleSearch(),
                properties.getGroups().getArticleSearch(),
                record.getId());
    }

    private Long parseOutboxId(Map<Object, Object> body) {
        Object raw = body.get("outboxId");
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException invalidMessage) {
            LOGGER.warn("event=article_search_stream_message_invalid_outbox_id raw={}", raw);
            return null;
        }
    }
}
