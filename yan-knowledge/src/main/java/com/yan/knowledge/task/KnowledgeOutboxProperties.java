package com.yan.knowledge.task;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "blog.knowledge.outbox")
public class KnowledgeOutboxProperties {

    private int leaseSeconds = 60;
    private int batchSize = 100;
    private int retryDelaySeconds = 10;
    private int maxRetryDelaySeconds = 300;
    private int maxRetry = 8;
    private final Stream stream = new Stream();

    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getRetryDelaySeconds() { return retryDelaySeconds; }
    public void setRetryDelaySeconds(int retryDelaySeconds) { this.retryDelaySeconds = retryDelaySeconds; }
    public int getMaxRetryDelaySeconds() { return maxRetryDelaySeconds; }
    public void setMaxRetryDelaySeconds(int maxRetryDelaySeconds) { this.maxRetryDelaySeconds = maxRetryDelaySeconds; }
    public int getMaxRetry() { return maxRetry; }
    public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
    public Stream getStream() { return stream; }

    public static class Stream {
        private boolean enabled;
        private String key = "yanblog:stream:knowledge-index";
        private String group = "yanblog-knowledge-index";
        private String consumer = "yanblog-admin";
        private int pollDelayMs = 200;
        private int consumerBatchSize = 50;
        private int consumerCount = 2;
        private long maxLen = 100_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getGroup() { return group; }
        public void setGroup(String group) { this.group = group; }
        public String getConsumer() { return consumer; }
        public void setConsumer(String consumer) { this.consumer = consumer; }
        public int getPollDelayMs() { return pollDelayMs; }
        public void setPollDelayMs(int pollDelayMs) { this.pollDelayMs = pollDelayMs; }
        public int getConsumerBatchSize() { return consumerBatchSize; }
        public void setConsumerBatchSize(int consumerBatchSize) { this.consumerBatchSize = consumerBatchSize; }
        public int getConsumerCount() { return consumerCount; }
        public void setConsumerCount(int consumerCount) { this.consumerCount = consumerCount; }
        public long getMaxLen() { return maxLen; }
        public void setMaxLen(long maxLen) { this.maxLen = maxLen; }
    }
}
