package com.knode.search.rebuild.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knode.search.rebuild")
public class SearchRebuildProperties {
    private boolean enabled = false;
    private boolean workerEnabled = false;
    private String instanceId = "";
    private int snapshotPageSize = 100;
    private int changeBatchLimit = 100;
    private long requestTimeoutMs = 20000L;
    private int jobLeaseSeconds = 60;
    private int verificationSampleSize = 20;
    private long pollDelayMs = 2000L;
    private int maxSwitchAttempts = 3;
    private int maxCandidateIndexDrainSteps = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public int getSnapshotPageSize() {
        return snapshotPageSize;
    }

    public void setSnapshotPageSize(int snapshotPageSize) {
        this.snapshotPageSize = snapshotPageSize;
    }

    public int getChangeBatchLimit() {
        return changeBatchLimit;
    }

    public void setChangeBatchLimit(int changeBatchLimit) {
        this.changeBatchLimit = changeBatchLimit;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getJobLeaseSeconds() {
        return jobLeaseSeconds;
    }

    public void setJobLeaseSeconds(int jobLeaseSeconds) {
        this.jobLeaseSeconds = jobLeaseSeconds;
    }

    public int getVerificationSampleSize() {
        return verificationSampleSize;
    }

    public void setVerificationSampleSize(int verificationSampleSize) {
        this.verificationSampleSize = verificationSampleSize;
    }

    public long getPollDelayMs() {
        return pollDelayMs;
    }

    public void setPollDelayMs(long pollDelayMs) {
        this.pollDelayMs = pollDelayMs;
    }

    public int getMaxSwitchAttempts() {
        return maxSwitchAttempts;
    }

    public void setMaxSwitchAttempts(int maxSwitchAttempts) {
        this.maxSwitchAttempts = maxSwitchAttempts;
    }

    public int getMaxCandidateIndexDrainSteps() {
        return maxCandidateIndexDrainSteps;
    }

    public void setMaxCandidateIndexDrainSteps(int maxCandidateIndexDrainSteps) {
        this.maxCandidateIndexDrainSteps = maxCandidateIndexDrainSteps;
    }
}
