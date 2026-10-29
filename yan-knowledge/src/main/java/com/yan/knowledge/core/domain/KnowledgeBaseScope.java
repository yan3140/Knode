package com.yan.knowledge.core.domain;

import java.util.Objects;
import java.util.Set;

/** Adapter 完成权限计算后的文档范围；检索器不得绕过这个范围。 */
public record KnowledgeBaseScope(
        String knowledgeBaseId,
        Set<String> documentIds
) {
    public KnowledgeBaseScope {
        if (Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null").isBlank()) {
            throw new IllegalArgumentException("knowledgeBaseId must not be blank");
        }
        documentIds = Set.copyOf(Objects.requireNonNull(documentIds, "documentIds must not be null"));
    }

    public boolean contains(String documentId) {
        return documentIds.contains(documentId);
    }
}
