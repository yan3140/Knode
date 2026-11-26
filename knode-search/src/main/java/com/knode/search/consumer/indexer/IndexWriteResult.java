package com.knode.search.consumer.indexer;

public record IndexWriteResult(Status status, String detail) {

    public static IndexWriteResult applied() {
        return new IndexWriteResult(Status.APPLIED, "APPLIED");
    }

    public static IndexWriteResult idempotentApplied() {
        return new IndexWriteResult(Status.IDEMPOTENT_APPLIED, "IDEMPOTENT_SAME_VERSION_VERIFIED");
    }

    public static IndexWriteResult superseded() {
        return new IndexWriteResult(Status.SUPERSEDED, "SUPERSEDED_BY_HIGHER_ES_VERSION");
    }

    public static IndexWriteResult conflictUnverified(String detail) {
        return new IndexWriteResult(Status.CONFLICT_UNVERIFIED, detail);
    }

    public static IndexWriteResult failed(String detail) {
        return new IndexWriteResult(Status.FAILED, detail);
    }

    public enum Status {
        APPLIED,
        IDEMPOTENT_APPLIED,
        SUPERSEDED,
        CONFLICT_UNVERIFIED,
        FAILED
    }
}
