package com.knode.search.rebuild.persistence;

public enum RebuildPhase {
    INIT,
    SNAPSHOT_IMPORTING,
    INCREMENTAL_CATCHUP,
    CANDIDATE_INDEXING,
    CUTOVER_PAUSING,
    FINAL_CATCHUP,
    VERIFYING_CANDIDATE,
    SWITCHING_ALIAS,
    CACHE_INVALIDATING,
    COMPLETED,
    EXPIRED_SUPERSEDED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == EXPIRED_SUPERSEDED || this == FAILED;
    }
}
