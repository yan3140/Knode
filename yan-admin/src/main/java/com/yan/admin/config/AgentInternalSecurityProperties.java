package com.yan.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "blog.agent.internal")
public class AgentInternalSecurityProperties {

    private boolean enabled;
    private String serviceKey = "";
    private String principalJwtSecret = "";
    private int principalJwtTtlSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(String serviceKey) {
        this.serviceKey = serviceKey;
    }

    public String getPrincipalJwtSecret() {
        return principalJwtSecret;
    }

    public void setPrincipalJwtSecret(String principalJwtSecret) {
        this.principalJwtSecret = principalJwtSecret;
    }

    public int getPrincipalJwtTtlSeconds() {
        return principalJwtTtlSeconds;
    }

    public void setPrincipalJwtTtlSeconds(int principalJwtTtlSeconds) {
        this.principalJwtTtlSeconds = principalJwtTtlSeconds;
    }
}
