package com.yan.notification;

import com.yan.domain.entity.NotificationDeliveryOutboxEvent;
import com.yan.mapper.NotificationDeliveryOutboxMapper;
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
import java.util.Map;

@Component
@ConditionalOnBean(NotificationDeliveryOutboxMessageGateway.class)
@ConditionalOnExpression("'${blog.reliable-task.redis-stream.enabled:false}' == 'true' and '${blog.notification.delivery-outbox.stream.enabled:false}' == 'true'")
public class NotificationDeliveryOutboxConsumer implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryOutboxConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final ReliableTaskRedisStreamProperties properties;
    private final NotificationDeliveryOutboxMapper outboxMapper;
    private final NotificationDeliveryOutboxService outboxService;
    private final int leaseSeconds;
    private final int batchSize;
    private final int consumerCount;

    public NotificationDeliveryOutboxConsumer(StringRedisTemplate redisTemplate,
                                              ReliableTaskRedisStreamProperties properties,
                                              NotificationDeliveryOutboxMapper outboxMapper,
                                              NotificationDeliveryOutboxService outboxService,
                                              @Value("${blog.notification.delivery-outbox.lease-seconds:30}") int leaseSeconds,
                                              @Value("${blog.notification.delivery-outbox.stream.consumer-batch-size:20}") int batchSize,
                                              @Value("${blog.notification.delivery-outbox.stream.consumer-count:2}") int consumerCount) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.outboxMapper = outboxMapper;
        this.outboxService = outboxService;
        this.leaseSeconds = leaseSeconds;
        this.batchSize = batchSize;
        this.consumerCount = Math.max(1, consumerCount);
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureGroup();
    }

    @Scheduled(fixedDelayString = "${blog.notification.delivery-outbox.stream.poll-delay-ms:200}")
    public void consume() {
        outboxMapper.recoverExpiredClaims();
        for (int i = 0; i < consumerCount; i++) {
            consumeAs(properties.getConsumers().getNotificationDelivery() + "-" + i);
        }
    }

    private void consumeAs(String consumerName) {
        java.util.List<MapRecord<String, Object, Object>> records;
        try {
            records = redisTemplate.opsForStream().read(
                    Consumer.from(properties.getGroups().getNotificationDelivery(), consumerName),
                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                            .count(batchSize)
                            .block(Duration.ofMillis(100)),
                    StreamOffset.create(properties.getStreams().getNotificationDelivery(), ReadOffset.lastConsumed()));
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
        for (MapRecord<String, Object, Object> record : records) {
            consume(record);
        }
    }

    private void consume(MapRecord<String, Object, Object> record) {
        Long outboxId = parseOutboxId(record.getValue());
        if (outboxId == null) {
            ack(record);
            return;
        }
        NotificationDeliveryOutboxEvent event = outboxMapper.selectById(outboxId);
        if (event == null) {
            LOGGER.warn("event=notification_delivery_stream_message_orphaned outboxId={}", outboxId);
            ack(record);
            return;
        }
        if (ReliableTaskStatus.PENDING.equals(event.getStatus())) {
            if (outboxMapper.claim(event.getId(), leaseSeconds) != 1) {
                ack(record);
                return;
            }
            outboxMapper.markProcessingStarted(event.getId());
            event = outboxMapper.selectById(outboxId);
        }
        if (!ReliableTaskStatus.PROCESSING.equals(event.getStatus())) {
            LOGGER.info("event=notification_delivery_stream_message_ignored outboxId={} status={}",
                    event.getId(), event.getStatus());
            ack(record);
            return;
        }
        outboxService.deliver(event);
        ack(record);
    }

    private void ensureGroup() {
        String stream = properties.getStreams().getNotificationDelivery();
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(stream))) {
            redisTemplate.opsForStream().add(org.springframework.data.redis.connection.stream.StreamRecords
                    .mapBacked(Map.of("_init", "1"))
                    .withStreamKey(stream));
        }
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.latest(),
                    properties.getGroups().getNotificationDelivery());
        } catch (RedisSystemException createFailure) {
            if (!containsMessage(createFailure, "BUSYGROUP")) {
                throw createFailure;
            }
        }
    }

    private void ack(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(
                properties.getStreams().getNotificationDelivery(),
                properties.getGroups().getNotificationDelivery(),
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
            LOGGER.warn("event=notification_delivery_stream_message_invalid_outbox_id raw={}", raw);
            return null;
        }
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
}
