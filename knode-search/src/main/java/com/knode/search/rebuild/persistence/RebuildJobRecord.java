package com.knode.search.rebuild.persistence;

public record RebuildJobRecord(
        String jobId,
        String aliasName,
        String activeLockKey,
        String clientRequestId,
        String snapshotId,
        Long snapshotExpiresAtMs,
        String snapshotPageCursor,
        boolean snapshotDone,
        int snapshotPagesImported,
        long snapshotItemsImported,
        String incrementalCursor,
        Long incrementalCursorExpiresAtMs,
        long lastCommitSequence,
        long incrementalCommitsApplied,
        long incrementalEventsApplied,
        long sourceGeneration,
        String sourcePhysicalIndex,
        long candidateGeneration,
        String candidatePhysicalIndex,
        RebuildPhase phase,
        String leaseOwner,
        Long leaseUntilMs,
        int switchAttempts,
        String lastError,
        String supersededByJobId,
        long createdAtMs,
        long updatedAtMs
) {
}
