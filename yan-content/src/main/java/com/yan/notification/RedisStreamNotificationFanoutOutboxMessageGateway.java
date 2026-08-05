package com.yan.notification;

import com.yan.domain.entity.NotificationOutboxEvent;
import com.yan.reliabletask.redisstream.ReliableTaskRedisStreamProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnExpression("'${blog.reliable-task.redis-stream.enabled:false}' == 'true' and '${blog.notification.outbox.stream.enabled:false}' == 'true'")
public class RedisStreamNotificationFanoutOutboxMessageGateway implements NotificationFanoutOutboxMessageGateway {
    private final StringRedisTemplate redisTemplate;
    private final ReliableTaskRedisStreamProperties properties;

    public RedisStreamNotificationFanoutOutboxMessageGateway(StringRedisTemplate redisTemplate,
                                                             ReliableTaskRedisStreamProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(NotificationOutboxEvent event) {
        RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "outboxId", event.getId().toString(),
                "articleId", event.getArticleId().toString(),
                "authorId", event.getAuthorId().toString(),
                "taskKey", event.getTaskKey() == null ? "" : event.getTaskKey()
        )).withStreamKey(properties.getStreams().getNotificationFanout()));
        if (recordId == null) {
            throw new IllegalStateException("Unable to append notification fanout outbox event to Redis Stream");
        }
        if (properties.getMaxLen() > 0) {
            redisTemplate.opsForStream().trim(properties.getStreams().getNotificationFanout(), properties.getMaxLen(), true);
        }
    }
}
