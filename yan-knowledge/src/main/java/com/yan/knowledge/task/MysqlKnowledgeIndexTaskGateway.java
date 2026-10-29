package com.yan.knowledge.task;

import com.yan.knowledge.core.domain.IndexTask;
import com.yan.knowledge.core.port.TaskGateway;
import com.yan.knowledge.mapper.KnowledgeIndexOutboxMapper;
import org.springframework.stereotype.Service;

@Service
public class MysqlKnowledgeIndexTaskGateway implements TaskGateway {

    private final KnowledgeIndexOutboxMapper outboxMapper;
    private final KnowledgeOutboxProperties properties;

    public MysqlKnowledgeIndexTaskGateway(KnowledgeIndexOutboxMapper outboxMapper,
                                          KnowledgeOutboxProperties properties) {
        this.outboxMapper = outboxMapper;
        this.properties = properties;
    }

    @Override
    public void enqueue(IndexTask task) {
        if (properties.getMaxRetry() < 1) {
            throw new IllegalArgumentException("Knowledge index maxRetry must be positive");
        }
        int affected = outboxMapper.enqueue(task.taskKey(), Long.valueOf(task.documentId()),
                Long.valueOf(task.documentVersionId()), task.operation().name(), properties.getMaxRetry());
        if (affected < 1) {
            throw new IllegalStateException("Unable to persist knowledge index outbox event");
        }
    }
}
