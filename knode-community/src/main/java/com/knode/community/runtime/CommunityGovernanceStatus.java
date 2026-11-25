package com.knode.community.runtime;

import com.knode.governance.GovernanceRules;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Surfaces knode-governance rule readiness in knode-community readiness probes when governance is enabled. */
@Component("communityGovernanceHealthIndicator")
public final class CommunityGovernanceStatus implements HealthIndicator {
    private final Environment env;
    private final ObjectProvider<GovernanceRules> rules;

    public CommunityGovernanceStatus(Environment env, ObjectProvider<GovernanceRules> rules) {
        this.env = env;
        this.rules = rules;
    }

    @Override
    public Health health() {
        if (!env.getProperty("knode.governance.enabled", Boolean.class, false)) {
            return Health.up().withDetail("status", "DISABLED").build();
        }
        GovernanceRules active = rules.getIfAvailable();
        if (active == null) {
            return Health.outOfService().withDetail("reason", "Governance rules unavailable").build();
        }
        return active.health();
    }
}
