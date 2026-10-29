package com.yan.knowledge.core.domain;

import java.util.Objects;

/** 标识一组不可混用的 Embedding、切块和关键词索引配置。 */
public record IndexIdentity(
        String embeddingProvider,
        String embeddingModel,
        int embeddingDimensions,
        String chunkingPolicyVersion,
        String keywordSchemaVersion
) {
    public IndexIdentity {
        requireText(embeddingProvider, "embeddingProvider");
        requireText(embeddingModel, "embeddingModel");
        if (embeddingDimensions < 0) {
            throw new IllegalArgumentException("embeddingDimensions must not be negative");
        }
        requireText(chunkingPolicyVersion, "chunkingPolicyVersion");
        requireText(keywordSchemaVersion, "keywordSchemaVersion");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
