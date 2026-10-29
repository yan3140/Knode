package com.yan.knowledge.task;

import com.yan.knowledge.domain.entity.KnowledgeIndexOutboxEvent;
import com.yan.knowledge.mapper.KnowledgeIndexOutboxMapper;
import com.yan.reliabletask.ReliableTaskStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Component
@ConditionalOnBean(KnowledgeIndexOutboxMessageGateway.class)
@ConditionalOnExpression("'${blog.knowledge.outbox.stream.enabled:false}' == 'true'")
public class KnowledgeIndexOutboxConsumer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexOutboxConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final KnowledgeOutboxProperties properties;
    private final KnowledgeIndexOutboxMapper outboxMapper;
    private final KnowledgeIndexOutboxProcessor processor;

    public KnowledgeIndexOutboxConsumer(StringRedisTemplate redisTemplate,
                                        KnowledgeOutboxProperties properties,
                                        KnowledgeIndexOutboxMapper outboxMapper,
                                        KnowledgeIndexOutboxProcessor processor) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.outboxMapper = outboxMapper;
        this.processor = processor;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureGroup();
    }

    @Scheduled(fixedDelayString = "${blog.knowledge.outbox.stream.poll-delay-ms:200}")
    public void consume() {
        outboxMapper.recoverExpiredClaims();
        int consumers = Math.max(1, properties.getStream().getConsumerCount());
        for (int index = 0; index < consumers; index++) {
            consumeAs(properties.getStream().getConsumer() + "-" + index);
        }
    }

    private void consumeAs(String consumerName) {
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redisTemplate.opsForStream().read(
                    Consumer.from(properties.getStream().getGroup(), consumerName),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                            .count(properties.getStream().getConsumerBatchSize())
                            .block(Duration.ofMillis(100)),
                    StreamOffset.create(properties.getStream().getKey(), ReadOffset.lastConsumed()));
        } catch (RedisSystemException failure) {
            if (containsMessage(failure, "NOGROUP")) {
                ensureGroup();
                return;
            }
            throw failure;
        }
        if (records == null || records.isEmpty()) {
            return;
        }
        List<KnowledgeIndexOutboxEvent> claimed = new ArrayList<>();
        List<MapRecord<String, Object, Object>> claimedRecords = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            KnowledgeIndexOutboxEvent event = claim(record);
            if (event != null) {
                claimed.add(event);
                claimedRecords.add(record);
            }
        }
        if (!claimed.isEmpty()) {
            processor.process(claimed);
        }
        claimedRecords.forEach(this::ack);
    }

    private KnowledgeIndexOutboxEvent claim(MapRecord<String, Object, Object> record) {
        Long outboxId = parseOutboxId(record.getValue());
        if (outboxId == null) {
            ack(record);
            return null;
        }
        KnowledgeIndexOutboxEvent event = outboxMapper.selectById(outboxId);
        if (event == null) {
            LOGGER.warn("event=knowledge_index_stream_message_orphaned outboxId={}", outboxId);
            ack(record);
            return null;
        }
        if (ReliableTaskStatus.PENDING.equals(event.getStatus())) {
            if (outboxMapper.claim(event.getId(), properties.getLeaseSeconds()) != 1) {
                ack(record);
                return null;
            }
            event = outboxMapper.selectById(outboxId);
        }
        if (!ReliableTaskStatus.PROCESSING.equals(event.getStatus())) {
            ack(record);
            return null;
        }
        return event;
    }

    private void ensureGroup() {
        String stream = properties.getStream().getKey();
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(stream))) {
            redisTemplate.opsForStream().add(org.springframework.data.redis.connection.stream.StreamRecords
                    .mapBacked(Map.of("_init", "1")).withStreamKey(stream));
        }
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.latest(), properties.getStream().getGroup());
        } catch (RedisSystemException failure) {
            if (!containsMessage(failure, "BUSYGROUP")) {
                throw failure;
            }
        }
    }

    private void ack(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(properties.getStream().getKey(),
                properties.getStream().getGroup(), record.getId());
    }

    private Long parseOutboxId(Map<Object, Object> body) {
        Object value = body.get("outboxId");
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            LOGGER.warn("event=knowledge_index_stream_message_invalid_outbox_id raw={}", value);
            return null;
        }
    }

    private boolean containsMessage(Throwable failure, String text) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
