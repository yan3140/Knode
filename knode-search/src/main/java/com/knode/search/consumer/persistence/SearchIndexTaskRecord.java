package com.knode.search.consumer.persistence;

public record SearchIndexTaskRecord(
        long indexGeneration,
        long articleId,
        String targetIndex,
        long targetProjectionRevision,
        SearchIndexOperation operation,
        String payloadJson,
        SearchIndexTaskStatus status,
        int attempts,
        int maxAttempts,
        long dueAtMs,
        Long leaseUntilMs,
        long claimGeneration,
        String lastError,
        long esAppliedRevision,
        CacheInvalidationStatus cacheInvalidationStatus,
        int cacheInvalidationAttempts,
        Long cacheInvalidationDueAtMs,
        String cacheLastError,
        long updatedAtMs
) {
}
