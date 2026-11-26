package com.knode.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knode.search.clients")
public class SearchClientsProperties {
    private String userOrigin = "";
    private String contentOrigin = "";
    private String serviceId = "knode-search";
    private String bootstrapSecret = "";
    private long connectTimeoutMs = 500L;
    private long userTokenTimeoutMs = 800L;
    private long contentVisibilityTimeoutMs = 1000L;
    private long contentSnapshotTimeoutMs = 15000L;
    private long tokenRefreshSkewSeconds = 10L;

    public String getUserOrigin() {
        return userOrigin;
    }

    public void setUserOrigin(String userOrigin) {
        this.userOrigin = userOrigin;
    }

    public String getContentOrigin() {
        return contentOrigin;
    }

    public void setContentOrigin(String contentOrigin) {
        this.contentOrigin = contentOrigin;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getBootstrapSecret() {
        return bootstrapSecret;
    }

    public void setBootstrapSecret(String bootstrapSecret) {
        this.bootstrapSecret = bootstrapSecret;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getUserTokenTimeoutMs() {
        return userTokenTimeoutMs;
    }

    public void setUserTokenTimeoutMs(long userTokenTimeoutMs) {
        this.userTokenTimeoutMs = userTokenTimeoutMs;
    }

    public long getContentVisibilityTimeoutMs() {
        return contentVisibilityTimeoutMs;
    }

    public void setContentVisibilityTimeoutMs(long contentVisibilityTimeoutMs) {
        this.contentVisibilityTimeoutMs = contentVisibilityTimeoutMs;
    }

    public long getContentSnapshotTimeoutMs() {
        return contentSnapshotTimeoutMs;
    }

    public void setContentSnapshotTimeoutMs(long contentSnapshotTimeoutMs) {
        this.contentSnapshotTimeoutMs = contentSnapshotTimeoutMs;
    }

    public long getTokenRefreshSkewSeconds() {
        return tokenRefreshSkewSeconds;
    }

    public void setTokenRefreshSkewSeconds(long tokenRefreshSkewSeconds) {
        this.tokenRefreshSkewSeconds = tokenRefreshSkewSeconds;
    }
}
