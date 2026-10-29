package com.yan.knowledge.task;

import com.yan.knowledge.domain.entity.KnowledgeIndexOutboxEvent;

public interface KnowledgeIndexOutboxMessageGateway {

    void publish(KnowledgeIndexOutboxEvent event);
}
