package com.yan.knowledge.core.domain;

import java.time.Instant;
import java.util.Objects;

/** 逻辑文档，不直接承载某个版本的可变索引内容。 */
public record KnowledgeDocument(
        String id,
        String knowledgeBaseId,
        String sourceType,
        String sourceUri,
        String contentHash,
        String currentVersionId,
        Instant createdAt,
        Instant updatedAt
) {
    public KnowledgeDocument {
        requireText(id, "id");
        requireText(knowledgeBaseId, "knowledgeBaseId");
        requireText(sourceType, "sourceType");
        requireText(contentHash, "contentHash");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
