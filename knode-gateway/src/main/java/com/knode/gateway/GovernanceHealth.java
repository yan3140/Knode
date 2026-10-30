package com.knode.gateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("governance")
public class GovernanceHealth implements HealthIndicator {
    private final ObjectProvider<NacosGatewayRules> source;
    public GovernanceHealth(ObjectProvider<NacosGatewayRules> source) { this.source = source; }
    @Override public Health health() {
        NacosGatewayRules rules = source.getIfAvailable();
        return rules == null ? Health.up().withDetail("rules", "disabled").build() : rules.health();
    }
}
