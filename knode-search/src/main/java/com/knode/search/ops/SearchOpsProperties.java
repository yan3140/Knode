package com.knode.search.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knode.search.ops")
public class SearchOpsProperties {
    private int defaultBatchRetryLimit = 50;
    private int maxBatchRetryLimit = 100;
    private long syncTimeoutMs = 5000L;

    public int getDefaultBatchRetryLimit() {
        return defaultBatchRetryLimit;
    }

    public void setDefaultBatchRetryLimit(int defaultBatchRetryLimit) {
        this.defaultBatchRetryLimit = defaultBatchRetryLimit;
    }

    public int getMaxBatchRetryLimit() {
        return maxBatchRetryLimit;
    }

    public void setMaxBatchRetryLimit(int maxBatchRetryLimit) {
        this.maxBatchRetryLimit = maxBatchRetryLimit;
    }

    public long getSyncTimeoutMs() {
        return syncTimeoutMs;
    }

    public void setSyncTimeoutMs(long syncTimeoutMs) {
        this.syncTimeoutMs = syncTimeoutMs;
    }
}
