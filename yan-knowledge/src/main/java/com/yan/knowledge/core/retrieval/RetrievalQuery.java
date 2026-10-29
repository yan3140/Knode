package com.yan.knowledge.core.retrieval;

import java.util.Map;
import java.util.Objects;

/** 跨项目共享的检索请求；业务字段通过 queryContext 扩展，不泄漏到公共模型。 */
public record RetrievalQuery(
        String knowledgeBaseId,
        String query,
        Map<String, String> queryContext,
        Map<String, String> filters,
        RetrievalMode requestedMode,
        int topK,
        int candidateK,
        ContextBudget contextBudget,
        String traceId
) {
    public RetrievalQuery {
        requireText(knowledgeBaseId, "knowledgeBaseId");
        requireText(query, "query");
        queryContext = Map.copyOf(Objects.requireNonNull(queryContext, "queryContext must not be null"));
        filters = Map.copyOf(Objects.requireNonNull(filters, "filters must not be null"));
        Objects.requireNonNull(requestedMode, "requestedMode must not be null");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (candidateK < topK) {
            throw new IllegalArgumentException("candidateK must be greater than or equal to topK");
        }
        Objects.requireNonNull(contextBudget, "contextBudget must not be null");
        requireText(traceId, "traceId");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
