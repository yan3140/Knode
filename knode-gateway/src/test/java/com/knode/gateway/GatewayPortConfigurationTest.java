package com.knode.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayPortConfigurationTest {
    @Test void gatewayPortHasAnIndependentDefaultAndAnEnvironmentOverride() throws Exception {
        var source = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).get(0);
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(source);
        assertThat(environment.getProperty("server.port")).isEqualTo("18089");
        environment.getPropertySources().addFirst(new MapPropertySource("override", Map.of("GATEWAY_PORT", "19234")));
        assertThat(environment.getProperty("server.port")).isEqualTo("19234");
    }
}
