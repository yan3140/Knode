package com.yan.knowledge.core.retrieval;

public enum DegradationReason {
    VECTOR_UNAVAILABLE,
    KEYWORD_UNAVAILABLE,
    EMBEDDING_TIMEOUT,
    EXACT_ONLY,
    FULL_CONTEXT_ONLY,
    CONTEXT_BUDGET_EXCEEDED,
    NO_RESULT
}
