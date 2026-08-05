package com.yan.search.outbox;

import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.reliabletask.redisstream.ReliableTaskRedisStreamProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnExpression("'${blog.reliable-task.redis-stream.enabled:false}' == 'true' and '${blog.search.outbox.stream.enabled:false}' == 'true'")
public class RedisStreamArticleSearchOutboxMessageGateway implements ArticleSearchOutboxMessageGateway {

    private final StringRedisTemplate redisTemplate;
    private final ReliableTaskRedisStreamProperties properties;

    public RedisStreamArticleSearchOutboxMessageGateway(StringRedisTemplate redisTemplate,
                                                        ReliableTaskRedisStreamProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(ArticleSearchOutboxEvent event) {
        RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "outboxId", event.getId().toString(),
                "articleId", event.getArticleId().toString(),
                "taskKey", event.getTaskKey() == null ? "" : event.getTaskKey()
        )).withStreamKey(properties.getStreams().getArticleSearch()));
        if (recordId == null) {
            throw new IllegalStateException("Unable to append article search outbox event to Redis Stream");
        }
        if (properties.getMaxLen() > 0) {
            redisTemplate.opsForStream().trim(properties.getStreams().getArticleSearch(), properties.getMaxLen(), true);
        }
    }
}
