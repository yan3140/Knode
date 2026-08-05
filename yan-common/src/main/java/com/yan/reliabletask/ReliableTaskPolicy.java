package com.yan.reliabletask;

/** Shared retry and dead-letter rules for reliable asynchronous tasks. */
public class ReliableTaskPolicy {
    private final int maxRetry;
    private final int retryDelaySeconds;
    private final int maxRetryDelaySeconds;

    public ReliableTaskPolicy(int maxRetry, int retryDelaySeconds, int maxRetryDelaySeconds) {
        if (maxRetry < 1 || retryDelaySeconds < 1 || maxRetryDelaySeconds < retryDelaySeconds) {
            throw new IllegalArgumentException("Reliable task retry configuration must be positive");
        }
        this.maxRetry = maxRetry;
        this.retryDelaySeconds = retryDelaySeconds;
        this.maxRetryDelaySeconds = maxRetryDelaySeconds;
    }

    public int maxRetry() {
        return maxRetry;
    }

    public int nextRetryDelaySeconds(Integer retryCount) {
        int attempts = retryCount == null ? 0 : Math.max(0, retryCount);
        long delay = retryDelaySeconds;
        for (int index = 0; index < attempts && delay < maxRetryDelaySeconds; index++) {
            delay = Math.min((long) maxRetryDelaySeconds, delay * 2);
        }
        return (int) delay;
    }

    public boolean willDeadLetter(Integer retryCount) {
        int attempts = retryCount == null ? 0 : Math.max(0, retryCount);
        return attempts + 1 >= maxRetry;
    }

    public String safeError(String reason) {
        String safeReason = reason == null ? "Unknown reliable task failure" : reason;
        return safeReason.substring(0, Math.min(1000, safeReason.length()));
    }
}
