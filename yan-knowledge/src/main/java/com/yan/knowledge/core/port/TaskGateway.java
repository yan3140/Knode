package com.yan.knowledge.core.port;

import com.yan.knowledge.core.domain.IndexTask;

public interface TaskGateway {

    void enqueue(IndexTask task);
}
