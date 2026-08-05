package com.yan.notification;

import com.yan.domain.entity.NotificationDeliveryOutboxEvent;
import com.yan.reliabletask.redisstream.ReliableTaskRedisStreamProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnExpression("'${blog.reliable-task.redis-stream.enabled:false}' == 'true' and '${blog.notification.delivery-outbox.stream.enabled:false}' == 'true'")
public class RedisStreamNotificationDeliveryOutboxMessageGateway implements NotificationDeliveryOutboxMessageGateway {
    private final StringRedisTemplate redisTemplate;
    private final ReliableTaskRedisStreamProperties properties;

    public RedisStreamNotificationDeliveryOutboxMessageGateway(StringRedisTemplate redisTemplate,
                                                               ReliableTaskRedisStreamProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(NotificationDeliveryOutboxEvent event) {
        RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "outboxId", event.getId().toString(),
                "receiverId", event.getReceiverId().toString(),
                "type", event.getType(),
                "taskKey", event.getTaskKey() == null ? "" : event.getTaskKey()
        )).withStreamKey(properties.getStreams().getNotificationDelivery()));
        if (recordId == null) {
            throw new IllegalStateException("Unable to append notification delivery outbox event to Redis Stream");
        }
        if (properties.getMaxLen() > 0) {
            redisTemplate.opsForStream().trim(properties.getStreams().getNotificationDelivery(), properties.getMaxLen(), true);
        }
    }
}
