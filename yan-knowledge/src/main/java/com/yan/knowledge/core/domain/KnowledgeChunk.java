package com.yan.knowledge.core.domain;

import java.util.Map;
import java.util.Objects;

/** 检索的最小事实单元，携带稳定 ID 和可追溯 metadata。 */
public record KnowledgeChunk(
        String id,
        String documentId,
        String documentVersionId,
        int chunkIndex,
        String content,
        String title,
        String section,
        String source,
        Map<String, String> metadata
) {
    public static String stableId(String documentId, String documentVersionId, int chunkIndex) {
        if (documentId == null || documentId.isBlank()
                || documentVersionId == null || documentVersionId.isBlank()
                || chunkIndex < 0) {
            throw new IllegalArgumentException("document and chunk identity must be valid");
        }
        return documentId + ":" + documentVersionId + ":" + chunkIndex;
    }

    public KnowledgeChunk {
        requireText(id, "id");
        requireText(documentId, "documentId");
        requireText(documentVersionId, "documentVersionId");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        requireText(content, "content");
        requireText(title, "title");
        requireText(section, "section");
        requireText(source, "source");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
