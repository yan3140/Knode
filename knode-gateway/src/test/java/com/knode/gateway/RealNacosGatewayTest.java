package com.knode.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.actuate.health.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnabledIfSystemProperty(named = "knode.it.nacos.enabled", matches = "true")
class RealNacosGatewayTest {
    @Test void dashboardPublishedRulesLoadAndSurviveClientRestart() throws Exception {
        String server = System.getProperty("knode.it.nacos.server", "");
        String namespace = System.getProperty("knode.it.nacos.namespace", "");
        String group = System.getProperty("knode.it.nacos.group", "");
        if (!server.matches("127\\.0\\.0\\.1:[0-9]+") || !namespace.startsWith("g4-it-") || !group.startsWith("G4_IT_"))
            throw new IllegalArgumentException("Only a specifically isolated loopback test instance is allowed");
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, server);
        properties.put(PropertyKeyConst.NAMESPACE, namespace);
        ConfigService first = NacosFactory.createConfigService(properties);
        NacosGatewayRules client = new NacosGatewayRules(first, new ObjectMapper(), group, 100, 500);
        try {
            client.run(null);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(client.health().getStatus()).isEqualTo(Status.UP);
                assertThat(GatewayRuleManager.getRulesForResource("g4-it-route")).hasSize(1);
                assertThat(GatewayRuleManager.getRulesForResource("g4-it-route").iterator().next().getCount()).isEqualTo(2.0);
                assertThat(GatewayApiDefinitionManager.getApiDefinition("g4-it-api")).isNotNull();
            });
        } finally {
            client.close();
        }
        GatewayRuleManager.loadRules(Set.of());
        GatewayApiDefinitionManager.loadApiDefinitions(Set.of());
        ConfigService second = NacosFactory.createConfigService(properties);
        NacosGatewayRules restarted = new NacosGatewayRules(second, new ObjectMapper(), group, 100, 500);
        try {
            restarted.run(null);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(restarted.health().getStatus()).isEqualTo(Status.UP);
                assertThat(GatewayRuleManager.getRulesForResource("g4-it-route")).hasSize(1);
                assertThat(GatewayApiDefinitionManager.getApiDefinition("g4-it-api")).isNotNull();
            });
        } finally {
            restarted.close();
            GatewayRuleManager.loadRules(Set.of());
            GatewayApiDefinitionManager.loadApiDefinitions(Set.of());
        }
    }
}
