package com.knode.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knode.search.cache")
public class SearchCacheProperties {
    private boolean enabled = false;
    private int maxPageSize = 20;
    private int admissionThreshold = 2;
    private int admissionTtlSeconds = 60;
    private int resultTtlSeconds = 60;
    private int emptyTtlSeconds = 30;
    private int jitterSeconds = 30;
    private final Redis redis = new Redis();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getAdmissionThreshold() {
        return admissionThreshold;
    }

    public void setAdmissionThreshold(int admissionThreshold) {
        this.admissionThreshold = admissionThreshold;
    }

    public int getAdmissionTtlSeconds() {
        return admissionTtlSeconds;
    }

    public void setAdmissionTtlSeconds(int admissionTtlSeconds) {
        this.admissionTtlSeconds = admissionTtlSeconds;
    }

    public int getResultTtlSeconds() {
        return resultTtlSeconds;
    }

    public void setResultTtlSeconds(int resultTtlSeconds) {
        this.resultTtlSeconds = resultTtlSeconds;
    }

    public int getEmptyTtlSeconds() {
        return emptyTtlSeconds;
    }

    public void setEmptyTtlSeconds(int emptyTtlSeconds) {
        this.emptyTtlSeconds = emptyTtlSeconds;
    }

    public int getJitterSeconds() {
        return jitterSeconds;
    }

    public void setJitterSeconds(int jitterSeconds) {
        this.jitterSeconds = jitterSeconds;
    }

    public Redis getRedis() {
        return redis;
    }

    public static class Redis {
        private String host = "127.0.0.1";
        private int port = 6379;
        private int database = 0;
        private String username = "";
        private String password = "";
        private boolean tls = false;
        private long connectTimeoutMs = 300L;
        private long commandTimeoutMs = 300L;

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
}
