package com.yan.knowledge.task;

import com.yan.knowledge.domain.entity.KnowledgeIndexOutboxEvent;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${blog.knowledge.outbox.stream.enabled:false}' == 'true'")
public class RedisStreamKnowledgeIndexOutboxMessageGateway implements KnowledgeIndexOutboxMessageGateway {

    private final StringRedisTemplate redisTemplate;
    private final KnowledgeOutboxProperties properties;

    public RedisStreamKnowledgeIndexOutboxMessageGateway(StringRedisTemplate redisTemplate,
                                                          KnowledgeOutboxProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(KnowledgeIndexOutboxEvent event) {
        RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "outboxId", event.getId().toString(),
                "taskKey", event.getTaskKey()
        )).withStreamKey(properties.getStream().getKey()));
        if (recordId == null) {
            throw new IllegalStateException("Unable to append knowledge index event to Redis Stream");
        }
        if (properties.getStream().getMaxLen() > 0) {
            redisTemplate.opsForStream().trim(properties.getStream().getKey(),
                    properties.getStream().getMaxLen(), true);
        }
    }
}
