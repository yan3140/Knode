package com.knode.gateway;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("authentication")
public class AuthenticationHealth implements HealthIndicator {
    @Override public Health health() {
        return Health.outOfService().withDetail("reason", "Authentication adapter is not ready").build();
    }
}
