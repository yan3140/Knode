package com.knode.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.actuate.health.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NacosGatewayRulesTest {
    private final ConfigService config = mock(ConfigService.class);
    private final NacosGatewayRules rules = new NacosGatewayRules(config, new ObjectMapper(), "KNODE_SENTINEL");

    @AfterEach void cleanup() {
        GatewayRuleManager.loadRules(java.util.Set.of());
        GatewayApiDefinitionManager.loadApiDefinitions(java.util.Set.of());
    }

    @Test void invalidUpdatesDoNotEraseLastValidRulesAndSetUnready() {
        rules.apply("knode-gateway.gw-flow.json", "[{\"resource\":\"profile-content\",\"count\":0}]");
        rules.apply("knode-gateway.gw-api-group.json", "[{\"apiName\":\"public-group\",\"predicateItems\":[{\"pattern\":\"/api/blog/**\",\"matchStrategy\":1}]}]");
        when(config.getServerStatus()).thenReturn("UP");
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        assertThat(GatewayRuleManager.getRulesForResource("profile-content")).hasSize(1);
        assertThat(GatewayApiDefinitionManager.getApiDefinition("public-group")).isNotNull();
        rules.apply("knode-gateway.gw-api-group.json", "[{\"apiName\":\"same\",\"predicateItems\":[{\"pattern\":\"/a\"}]},{\"apiName\":\"same\",\"predicateItems\":[{\"pattern\":\"/b\"}]}]");
        assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(GatewayApiDefinitionManager.getApiDefinition("public-group")).isNotNull();
        rules.apply("knode-gateway.gw-api-group.json", "[{\"apiName\":\"bad-regex\",\"predicateItems\":[{\"pattern\":\"[\",\"matchStrategy\":2}]}]");
        assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(GatewayApiDefinitionManager.getApiDefinition("public-group")).isNotNull();
        rules.apply("knode-gateway.gw-api-group.json", "[{\"apiName\":\"invalid\"}]");
        assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(GatewayApiDefinitionManager.getApiDefinition("public-group")).isNotNull();
        rules.apply("knode-gateway.gw-api-group.json", "[{\"apiName\":\"public-group\",\"predicateItems\":[{\"pattern\":\"/api/blog/**\",\"matchStrategy\":1}]}]");
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        rules.apply("knode-gateway.gw-flow.json", "{broken");
        assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(GatewayRuleManager.getRulesForResource("profile-content")).hasSize(1);
        rules.apply("knode-gateway.gw-flow.json", null);
        assertThat(GatewayRuleManager.getRulesForResource("profile-content")).hasSize(1);
        rules.apply("knode-gateway.gw-flow.json", "[]");
        assertThat(GatewayRuleManager.getRulesForResource("profile-content")).isEmpty();
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        when(config.getServerStatus()).thenReturn("DOWN");
        assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test void missingIsolatedNamespaceFailsBeforeClientCreation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new NacosRuleConfiguration().governanceConfigService("127.0.0.1:8848", "", "", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void initialFailureIsVisibleAndListenerCanRecover() throws Exception {
        when(config.getServerStatus()).thenReturn("UP");
        when(config.getConfigAndSignListener(anyString(), anyString(), anyLong(), any(Listener.class)))
                .thenAnswer(invocation -> invocation.getArgument(0).equals("knode-gateway.gw-flow.json") ? null : "[]");
        rules.run(null);
        assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        ArgumentCaptor<Listener> listener = ArgumentCaptor.forClass(Listener.class);
        verify(config).getConfigAndSignListener(eq("knode-gateway.gw-flow.json"), eq("KNODE_SENTINEL"), eq(3000L), listener.capture());
        listener.getValue().receiveConfigInfo("[]");
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        rules.close();
        verify(config).shutDown();
    }
}
