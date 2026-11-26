package com.knode.search.consumer.persistence;

public record IndexGenerationState(
        String aliasName,
        long activeGeneration,
        String activePhysicalIndex,
        boolean requireColdVisibilityGuard,
        CutoverState cutoverState,
        String cutoverJobId,
        Long previousGeneration,
        String previousPhysicalIndex,
        Long targetGeneration,
        String targetPhysicalIndex,
        Long cutoverStartedAtMs,
        CacheInvalidationStatus activeCacheInvalidationStatus,
        int activeCacheInvalidationAttempts,
        Long activeCacheInvalidationDueAtMs,
        String activeCacheLastError,
        String activeCutoverBypassOwner,
        long updatedAtMs
) {
    public enum CutoverState {
        NORMAL,
        CUTOVER_PAUSED,
        SWITCHING_ALIAS
    }

    public boolean isNormal() {
        return cutoverState == CutoverState.NORMAL;
    }
}
