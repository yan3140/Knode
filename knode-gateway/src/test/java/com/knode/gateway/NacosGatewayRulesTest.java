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
    private final ObjectMapper mapper = new ObjectMapper();
    private final NacosGatewayRules rules = new NacosGatewayRules(config, mapper, "KNODE_SENTINEL");

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

    @Test void invalidGatewayModesAndNonfiniteCountsRetainLastValidRules() {
        when(config.getServerStatus()).thenReturn("UP");
        rules.apply("knode-gateway.gw-flow.json", "[{\"resource\":\"r\",\"count\":2}]");
        rules.apply("knode-gateway.gw-api-group.json", "[]");
        for (String bad : java.util.List.of(
                "[{\"resource\":\"r\",\"resourceMode\":3,\"count\":1}]",
                "[{\"resource\":\"r\",\"grade\":4,\"count\":1}]",
                "[{\"resource\":\"r\",\"count\":1e999}]",
                "[{\"resource\":\"r\",\"count\":1,\"paramItem\":{\"parseStrategy\":9}}]")) {
            rules.apply("knode-gateway.gw-flow.json", bad);
            assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(GatewayRuleManager.getRulesForResource("r")).hasSize(1);
            assertThat(GatewayRuleManager.getRulesForResource("r").iterator().next().getCount()).isEqualTo(2);
        }
        rules.apply("knode-gateway.gw-api-group.json",
                "[{\"apiName\":\"a\",\"predicateItems\":[{\"pattern\":\"/x\",\"matchStrategy\":\"bad\"}]}]");
        assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }


    @Test void rejectsAmbiguousJsonForBothRuleKindsWithoutLosingLastGood() {
        assertThat(mapper.isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).isFalse();
        assertThat(mapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
        when(config.getServerStatus()).thenReturn("UP");
        String flow = "[{\"resource\":\"r\",\"count\":2}]";
        String api = "[{\"apiName\":\"api\",\"predicateItems\":[{\"pattern\":\"/a\"}]}]";
        rules.apply("knode-gateway.gw-flow.json", flow);
        rules.apply("knode-gateway.gw-api-group.json", api);
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        for (String bad : java.util.List.of(flow + " []", flow + " garbage",
                "[{\"resource\":\"r\",\"count\":2,\"count\":9}]")) {
            rules.apply("knode-gateway.gw-flow.json", bad);
            assertThat(rules.health().getStatus()).as(bad).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(GatewayRuleManager.getRulesForResource("r").iterator().next().getCount()).isEqualTo(2);
        }
        rules.apply("knode-gateway.gw-flow.json", flow + " \n");
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        for (String bad : java.util.List.of(api + " {}", api + " garbage",
                "[{\"apiName\":\"api\",\"predicateItems\":[{\"pattern\":\"/a\",\"pattern\":\"/b\"}]}]")) {
            rules.apply("knode-gateway.gw-api-group.json", bad);
            assertThat(rules.health().getStatus()).as(bad).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(GatewayApiDefinitionManager.getApiDefinition("api")).isNotNull();
        }
        rules.apply("knode-gateway.gw-api-group.json", api + " \n");
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test void retriesListenerWithoutLosingRulesAndReloadsOnRestart() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getServerStatus()).thenReturn("UP");
        java.util.concurrent.atomic.AtomicInteger flowAttempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch failed = new java.util.concurrent.CountDownLatch(1);
        String flow = "[{\"resource\":\"profile-content\",\"count\":2}]";
        when(service.getConfigAndSignListener(anyString(), anyString(), anyLong(), any(Listener.class)))
                .thenAnswer(call -> {
                    if (call.getArgument(0).equals("knode-gateway.gw-flow.json") && flowAttempts.getAndIncrement() == 0) {
                        failed.countDown();
                        throw new IllegalStateException("temporary failure");
                    }
                    return call.getArgument(0).equals("knode-gateway.gw-flow.json") ? flow : "[]";
                });
        NacosGatewayRules recovering = new NacosGatewayRules(service, new ObjectMapper(), "KNODE_SENTINEL", 300, 300);
        recovering.apply("knode-gateway.gw-flow.json", flow);
        recovering.run(null);
        assertThat(failed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(GatewayRuleManager.getRulesForResource("profile-content")).hasSize(1);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(
                () -> assertThat(recovering.health().getStatus()).isEqualTo(Status.UP));
        assertThat(flowAttempts.get()).isGreaterThanOrEqualTo(2);
        recovering.close();

        GatewayRuleManager.loadRules(java.util.Set.of());
        NacosGatewayRules restarted = new NacosGatewayRules(service, new ObjectMapper(), "KNODE_SENTINEL", 20, 100);
        restarted.run(null);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(restarted.health().getStatus()).isEqualTo(Status.UP);
            assertThat(GatewayRuleManager.getRulesForResource("profile-content")).hasSize(1);
        });
        restarted.close();
    }

    @Test void officialAntStrategyWithoutWildcardIsAccepted() {
        rules.apply("knode-gateway.gw-api-group.json",
                "[{\"apiName\":\"plain-ant\",\"predicateItems\":[{\"pattern\":\"/api/blog/plain\",\"matchStrategy\":1}]}]");
        assertThat(GatewayApiDefinitionManager.getApiDefinition("plain-ant")).isNotNull();
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
        verify(config, timeout(2000)).getConfigAndSignListener(eq("knode-gateway.gw-flow.json"), eq("KNODE_SENTINEL"), eq(3000L), listener.capture());
        listener.getValue().receiveConfigInfo("[]");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(
                () -> assertThat(rules.health().getStatus()).isEqualTo(Status.UP));
        rules.close();
        verify(config).shutDown();
    }
}
