package com.yan.knowledge.core.retrieval;

/** 统一使用 Token 预算，避免不同 Adapter 用字符数做出不可比较的路由判断。 */
public record ContextBudget(
        long maxInputTokens,
        long reservedOutputTokens,
        long safetyMarginTokens
) {
    public ContextBudget {
        if (maxInputTokens < 0 || reservedOutputTokens < 0 || safetyMarginTokens < 0) {
            throw new IllegalArgumentException("token budget values must not be negative");
        }
        if (availableInputTokens() < 0) {
            throw new IllegalArgumentException("reserved and safety tokens exceed max input tokens");
        }
    }

    public long availableInputTokens() {
        return maxInputTokens - reservedOutputTokens - safetyMarginTokens;
    }
}
