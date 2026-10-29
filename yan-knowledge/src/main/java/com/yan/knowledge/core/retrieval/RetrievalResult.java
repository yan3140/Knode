package com.yan.knowledge.core.retrieval;

import com.yan.knowledge.core.domain.KnowledgeCitation;

import java.util.List;
import java.util.Objects;

/** 检索结果记录实际模式和降级原因，供诊断证据链观察。 */
public record RetrievalResult(
        RetrievalMode requestedMode,
        RetrievalMode selectedMode,
        List<KnowledgeCitation> citations,
        boolean degraded,
        List<DegradationReason> degradationReasons,
        long totalElapsedMillis,
        String traceId
) {
    public RetrievalResult {
        Objects.requireNonNull(requestedMode, "requestedMode must not be null");
        Objects.requireNonNull(selectedMode, "selectedMode must not be null");
        citations = List.copyOf(Objects.requireNonNull(citations, "citations must not be null"));
        degradationReasons = List.copyOf(Objects.requireNonNull(degradationReasons, "degradationReasons must not be null"));
        if (totalElapsedMillis < 0) {
            throw new IllegalArgumentException("totalElapsedMillis must not be negative");
        }
        if (Objects.requireNonNull(traceId, "traceId must not be null").isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
    }
}
