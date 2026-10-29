package com.yan.knowledge.core.domain;

import java.time.Instant;
import java.util.Objects;

/** 不可变文档版本；索引任务必须携带这个版本的配置快照。 */
public record KnowledgeDocumentVersion(
        String id,
        String documentId,
        int versionNo,
        String contentHash,
        String title,
        String content,
        String sourceUri,
        DocumentVersionStatus status,
        IndexProfile indexProfile,
        IndexIdentity indexIdentity,
        IndexAvailability availability,
        Instant createdAt
) {
    public KnowledgeDocumentVersion {
        requireText(id, "id");
        requireText(documentId, "documentId");
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be positive");
        }
        requireText(contentHash, "contentHash");
        requireText(title, "title");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(indexProfile, "indexProfile must not be null");
        Objects.requireNonNull(indexIdentity, "indexIdentity must not be null");
        Objects.requireNonNull(availability, "availability must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
