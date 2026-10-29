package com.yan.knowledge.core.domain;

/** 根据索引启用策略把两个索引状态收敛为文档版本状态。 */
public record IndexAvailabilityPolicy(
        boolean requireKeyword,
        boolean requireVector,
        boolean allowPartial
) {
    public DocumentVersionStatus resolveStatus(IndexAvailability availability) {
        if (availability == null) {
            throw new IllegalArgumentException("index availability must not be null");
        }
        boolean requiredKeywordReady = !requireKeyword
                || availability.keyword() == IndexAvailabilityState.READY;
        boolean requiredVectorReady = !requireVector
                || availability.vector() == IndexAvailabilityState.READY;
        boolean requiredIndexFailed = (requireKeyword
                && availability.keyword() == IndexAvailabilityState.FAILED)
                || (requireVector && availability.vector() == IndexAvailabilityState.FAILED);

        if (requiredIndexFailed) {
            return DocumentVersionStatus.FAILED;
        }
        if (availability.hasPendingIndex()) {
            return DocumentVersionStatus.INDEXING;
        }
        if (availability.allEnabledIndexesReady()) {
            return DocumentVersionStatus.READY;
        }
        if (allowPartial && requiredKeywordReady && requiredVectorReady && availability.hasUsableIndex()) {
            return DocumentVersionStatus.PARTIAL_READY;
        }
        return DocumentVersionStatus.FAILED;
    }
}
