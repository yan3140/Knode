package com.yan.knowledge.core.retrieval;

import java.util.Objects;

/** 召回器候选；原始分数、召回排名和最终融合分数保持不同语义。 */
public record RetrievalCandidate(
        String chunkId,
        String documentId,
        String documentVersionId,
        String content,
        String title,
        String section,
        String source,
        Integer keywordRank,
        Integer vectorRank,
        boolean exactMatch,
        ExactMatchType exactType,
        Double vectorSimilarity,
        Double keywordScore,
        Double rrfScore,
        Double finalScore
) {
    public RetrievalCandidate {
        requireText(chunkId, "chunkId");
        requireText(documentId, "documentId");
        requireText(documentVersionId, "documentVersionId");
        Objects.requireNonNull(content, "content must not be null");
        requireText(title, "title");
        requireText(section, "section");
        requireText(source, "source");
        validateRank(keywordRank, "keywordRank");
        validateRank(vectorRank, "vectorRank");
        validateScore(vectorSimilarity, "vectorSimilarity");
        validateScore(keywordScore, "keywordScore");
        validateScore(rrfScore, "rrfScore");
        validateScore(finalScore, "finalScore");
    }

    RetrievalCandidate withFusion(Integer mergedKeywordRank,
                                  Integer mergedVectorRank,
                                  boolean mergedExactMatch,
                                  ExactMatchType mergedExactType,
                                  Double mergedVectorSimilarity,
                                  Double mergedKeywordScore,
                                  double mergedRrfScore) {
        return new RetrievalCandidate(
                chunkId,
                documentId,
                documentVersionId,
                content,
                title,
                section,
                source,
                mergedKeywordRank,
                mergedVectorRank,
                mergedExactMatch,
                mergedExactType,
                mergedVectorSimilarity,
                mergedKeywordScore,
                mergedRrfScore,
                mergedRrfScore);
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void validateRank(Integer value, String name) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(name + " must start at 1");
        }
    }

    private static void validateScore(Double value, String name) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
