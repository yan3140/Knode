package com.knode.search.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knode.search.consumer")
public class SearchConsumerProperties {
    private boolean enabled = false;
    private DataSourceProps datasource = new DataSourceProps();
    private StreamProps stream = new StreamProps();
    private IndexerProps indexer = new IndexerProps();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DataSourceProps getDatasource() {
        return datasource;
    }

    public void setDatasource(DataSourceProps datasource) {
        this.datasource = datasource == null ? new DataSourceProps() : datasource;
    }

    public StreamProps getStream() {
        return stream;
    }

    public void setStream(StreamProps stream) {
        this.stream = stream == null ? new StreamProps() : stream;
    }

    public IndexerProps getIndexer() {
        return indexer;
    }

    public void setIndexer(IndexerProps indexer) {
        this.indexer = indexer == null ? new IndexerProps() : indexer;
    }

    public static class DataSourceProps {
        private String url = "";
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        private String username = "";
        private String password = "";
        private int maximumPoolSize = 10;
        private long connectionTimeoutMs = 3000L;
        private int transactionTimeoutSeconds = 5;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }

        public int getTransactionTimeoutSeconds() {
            return transactionTimeoutSeconds;
        }

        public void setTransactionTimeoutSeconds(int transactionTimeoutSeconds) {
            this.transactionTimeoutSeconds = transactionTimeoutSeconds;
        }
    }

    public static class StreamProps {
        private boolean enabled = false;
        private boolean workerEnabled = false;
        private String group = "knode-search-v1";
        private String consumerName = "";
        private int batchSize = 10;
        private long blockTimeoutMs = 200L;
        private long minIdleMs = 30000L;
        private long pollDelayMs = 1000L;
        private RedisProps redis = new RedisProps();

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

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getConsumerName() {
            return consumerName;
        }

        public void setConsumerName(String consumerName) {
            this.consumerName = consumerName;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getBlockTimeoutMs() {
            return blockTimeoutMs;
        }

        public void setBlockTimeoutMs(long blockTimeoutMs) {
            this.blockTimeoutMs = blockTimeoutMs;
        }

        public long getMinIdleMs() {
            return minIdleMs;
        }

        public void setMinIdleMs(long minIdleMs) {
            this.minIdleMs = minIdleMs;
        }

        public long getPollDelayMs() {
            return pollDelayMs;
        }

        public void setPollDelayMs(long pollDelayMs) {
            this.pollDelayMs = pollDelayMs;
        }

        public RedisProps getRedis() {
            return redis;
        }

        public void setRedis(RedisProps redis) {
            this.redis = redis == null ? new RedisProps() : redis;
        }
    }

    public static class RedisProps {
        private String host = "127.0.0.1";
        private int port = 6379;
        private int database = 0;
        private String username = "";
        private String password = "";
        private boolean tls = false;
        private long connectTimeoutMs = 400L;
        private long commandTimeoutMs = 800L;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isTls() {
            return tls;
        }

        public void setTls(boolean tls) {
            this.tls = tls;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getCommandTimeoutMs() {
            return commandTimeoutMs;
        }

        public void setCommandTimeoutMs(long commandTimeoutMs) {
            this.commandTimeoutMs = commandTimeoutMs;
        }
    }

    public static class IndexerProps {
        private boolean enabled = false;
        private boolean workerEnabled = false;
        private int batchSize = 10;
        private int leaseSeconds = 30;
        private int maxAttempts = 5;
        private long baseRetryDelayMs = 1000L;
        private long maxRetryDelayMs = 60000L;
        private long pollDelayMs = 1000L;
        private String refreshPolicy = "wait_for";
        private int cacheCompensationBatchSize = 20;
        private long cacheCompensationBaseDelayMs = 1000L;
        private long cacheCompensationMaxDelayMs = 30000L;

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

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBaseRetryDelayMs() {
            return baseRetryDelayMs;
        }

        public void setBaseRetryDelayMs(long baseRetryDelayMs) {
            this.baseRetryDelayMs = baseRetryDelayMs;
        }

        public long getMaxRetryDelayMs() {
            return maxRetryDelayMs;
        }

        public void setMaxRetryDelayMs(long maxRetryDelayMs) {
            this.maxRetryDelayMs = maxRetryDelayMs;
        }

        public long getPollDelayMs() {
            return pollDelayMs;
        }

        public void setPollDelayMs(long pollDelayMs) {
            this.pollDelayMs = pollDelayMs;
        }

        public String getRefreshPolicy() {
            return refreshPolicy;
        }

        public void setRefreshPolicy(String refreshPolicy) {
            this.refreshPolicy = refreshPolicy;
        }

        public int getCacheCompensationBatchSize() {
            return cacheCompensationBatchSize;
        }

        public void setCacheCompensationBatchSize(int cacheCompensationBatchSize) {
            this.cacheCompensationBatchSize = cacheCompensationBatchSize;
        }

        public long getCacheCompensationBaseDelayMs() {
            return cacheCompensationBaseDelayMs;
        }

        public void setCacheCompensationBaseDelayMs(long cacheCompensationBaseDelayMs) {
            this.cacheCompensationBaseDelayMs = cacheCompensationBaseDelayMs;
        }

        public long getCacheCompensationMaxDelayMs() {
            return cacheCompensationMaxDelayMs;
        }

        public void setCacheCompensationMaxDelayMs(long cacheCompensationMaxDelayMs) {
            this.cacheCompensationMaxDelayMs = cacheCompensationMaxDelayMs;
        }
    }
}
