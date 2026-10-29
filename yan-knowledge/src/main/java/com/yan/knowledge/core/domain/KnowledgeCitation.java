package com.yan.knowledge.core.domain;

import com.yan.knowledge.core.retrieval.ExactMatchType;

import java.util.Objects;

/** 对外引用模型；finalScore 只表示最终排序分数，不冒充向量相似度。 */
public record KnowledgeCitation(
        String chunkId,
        String documentId,
        String documentVersionId,
        String title,
        String section,
        String source,
        String content,
        Double vectorSimilarity,
        Double keywordScore,
        Double rrfScore,
        Double finalScore,
        boolean exactMatch,
        ExactMatchType exactType
) {
    public KnowledgeCitation {
        requireText(chunkId, "chunkId");
        requireText(documentId, "documentId");
        requireText(documentVersionId, "documentVersionId");
        requireText(title, "title");
        requireText(section, "section");
        requireText(source, "source");
        Objects.requireNonNull(content, "content must not be null");
        validateScore(vectorSimilarity, "vectorSimilarity");
        validateScore(keywordScore, "keywordScore");
        validateScore(rrfScore, "rrfScore");
        validateScore(finalScore, "finalScore");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void validateScore(Double value, String name) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
