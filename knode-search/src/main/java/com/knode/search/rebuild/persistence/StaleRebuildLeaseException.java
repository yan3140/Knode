package com.knode.search.rebuild.persistence;

public class StaleRebuildLeaseException extends RuntimeException {
    private final String jobId;
    private final String reason;

    public StaleRebuildLeaseException(String jobId, String reason) {
        super("Rebuild job lease fenced for jobId=" + jobId + ": " + reason);
        this.jobId = jobId;
        this.reason = reason;
    }

    public String jobId() {
        return jobId;
    }

    public String reason() {
        return reason;
    }
}
