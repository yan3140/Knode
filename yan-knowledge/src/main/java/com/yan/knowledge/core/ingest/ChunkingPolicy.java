package com.yan.knowledge.core.ingest;

import java.util.Objects;

/** 切块参数必须随版本冻结，策略变化应产生新 documentVersionId。 */
public record ChunkingPolicy(
        int targetCharacters,
        int overlapCharacters,
        String policyVersion
) {
    public ChunkingPolicy {
        if (targetCharacters < 1 || overlapCharacters < 0 || overlapCharacters >= targetCharacters) {
            throw new IllegalArgumentException("chunking policy sizes are invalid");
        }
        if (Objects.requireNonNull(policyVersion, "policyVersion must not be null").isBlank()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
    }

    public static ChunkingPolicy defaultPolicy() {
        return new ChunkingPolicy(700, 100, "markdown-heading-v1");
    }
}
