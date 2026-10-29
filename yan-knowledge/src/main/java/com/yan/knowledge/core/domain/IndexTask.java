package com.yan.knowledge.core.domain;

import java.time.Instant;
import java.util.Objects;

/** 可持久化的索引任务事实；taskKey 是跨重试和 Adapter 的幂等身份。 */
public record IndexTask(
        String taskKey,
        String documentId,
        String documentVersionId,
        IndexOperation operation,
        IndexIdentity indexIdentity,
        Instant createdAt
) {
    public IndexTask {
        requireText(taskKey, "taskKey");
        requireText(documentId, "documentId");
        requireText(documentVersionId, "documentVersionId");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(indexIdentity, "indexIdentity must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
