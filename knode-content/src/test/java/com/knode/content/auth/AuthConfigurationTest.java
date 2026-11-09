package com.knode.content.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class AuthConfigurationTest {
    @Test void missingRequiredPropertiesFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(AuthConfiguration.class)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test void disabledTlsFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(AuthConfiguration.class)
                .withPropertyValues(
                        "KNODE_ENVIRONMENT=test", "KNODE_AUTH_REDIS_HOST=127.0.0.1",
                        "KNODE_AUTH_REDIS_PORT=1", "KNODE_AUTH_REDIS_USERNAME=test",
                        "KNODE_AUTH_REDIS_PASSWORD=test", "spring.data.redis.ssl.enabled=false",
                        "KNODE_CONTENT_JDBC_URL=jdbc:h2:mem:test", "KNODE_CONTENT_DB_USERNAME=sa",
                        "KNODE_CONTENT_DB_PASSWORD=test")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    while (failure.getCause() != null) failure = failure.getCause();
                    assertEquals("Missing or invalid required content configuration", failure.getMessage());
                });
    }
}
