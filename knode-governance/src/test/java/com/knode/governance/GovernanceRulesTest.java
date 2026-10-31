package com.knode.governance;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GovernanceRulesTest {
    final ConfigService config = mock(ConfigService.class);
    final ObjectMapper mapper = new ObjectMapper();
    final GovernanceRules rules = new GovernanceRules(config, mapper, "demo", "KNODE_SENTINEL", 30, 100);
    @AfterEach void clean() {
        rules.close();
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }
    @Test void rejectsWholeInvalidArrayRetainsLastValidAndClearsExplicitly() {
        when(config.getServerStatus()).thenReturn("UP");
        rules.apply("demo.flow.json", "[{\"resource\":\"HTTP GET /x\",\"grade\":1,\"count\":2}]");
        rules.apply("demo.degrade.json", "[]");
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        for (String bad : List.of(
                "[{\"resource\":\"HTTP GET /x\",\"grade\":99,\"count\":1}]",
                "[{\"resource\":\"HTTP GET /x\",\"grade\":1,\"count\":1e999}]",
                "[{\"resource\":\"HTTP GET /x\",\"grade\":1,\"count\":1,\"strategy\":1}]",
                "[{\"resource\":\"HTTP GET /x\",\"grade\":1,\"count\":1,\"clusterMode\":true}]",
                "[{\"resource\":\"HTTP GET /x\",\"grade\":1,\"count\":1,\"controlBehavior\":2,\"maxQueueingTimeMs\":1001}]",
                "[{\"resource\":\"ok\",\"count\":2},{\"resource\":\"bad\",\"grade\":77}]")) {
            rules.apply("demo.flow.json", bad);
            assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(FlowRuleManager.getRules()).hasSize(1);
            assertThat(FlowRuleManager.getRules().get(0).getCount()).isEqualTo(2);
        }
        rules.apply("demo.flow.json", null);
        assertThat(FlowRuleManager.getRules()).hasSize(1);
        rules.apply("demo.flow.json", "[]");
        assertThat(FlowRuleManager.getRules()).isEmpty();
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        rules.apply("demo.degrade.json", "[{\"resource\":\"x\",\"grade\":2,\"count\":1,\"timeWindow\":1}]");
        assertThat(DegradeRuleManager.getRules()).hasSize(1);
        rules.apply("demo.degrade.json", "[{\"resource\":\"x\",\"grade\":1,\"count\":2,\"timeWindow\":1}]");
        assertThat(rules.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(DegradeRuleManager.getRules()).hasSize(1);
        assertThat(DegradeRuleManager.getRules().get(0).getGrade()).isEqualTo(2);
        rules.apply("demo.degrade.json", "[]");
        assertThat(DegradeRuleManager.getRules()).isEmpty();
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test void rejectsAmbiguousJsonForBothRuleKindsWithoutLosingLastGood() {
        assertThat(mapper.isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).isFalse();
        assertThat(mapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
        when(config.getServerStatus()).thenReturn("UP");
        String flow = "[{\"resource\":\"x\",\"grade\":1,\"count\":2}]";
        String degrade = "[{\"resource\":\"x\",\"grade\":2,\"count\":1,\"timeWindow\":1}]";
        rules.apply("demo.flow.json", flow);
        rules.apply("demo.degrade.json", degrade);
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        for (String bad : List.of(flow + " []", flow + " garbage",
                "[{\"resource\":\"x\",\"grade\":1,\"count\":2,\"count\":9}]")) {
            rules.apply("demo.flow.json", bad);
            assertThat(rules.health().getStatus()).as(bad).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(FlowRuleManager.getRules().get(0).getCount()).isEqualTo(2);
        }
        rules.apply("demo.flow.json", flow + " \n");
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
        for (String bad : List.of(degrade + " {}", degrade + " garbage",
                "[{\"resource\":\"x\",\"grade\":2,\"count\":1,\"count\":9,\"timeWindow\":1}]")) {
            rules.apply("demo.degrade.json", bad);
            assertThat(rules.health().getStatus()).as(bad).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(DegradeRuleManager.getRules().get(0).getCount()).isEqualTo(1);
        }
        rules.apply("demo.degrade.json", degrade + " \n");
        assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
    }
    @Test void registrationFailureRetriesAndReloadsOnNewInstance() throws Exception {
        when(config.getServerStatus()).thenReturn("UP");
        AtomicInteger flowCalls = new AtomicInteger();
        when(config.getConfigAndSignListener(anyString(), anyString(), anyLong(), any(Listener.class)))
            .thenAnswer(call -> {
                if (call.getArgument(0).equals("demo.flow.json") && flowCalls.getAndIncrement() == 0)
                    throw new IllegalStateException("offline");
                return call.getArgument(0).equals("demo.flow.json")
                    ? "[{\"resource\":\"test\",\"grade\":1,\"count\":2}]" : "[]";
            });
        rules.run(null);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(rules.health().getStatus()).isEqualTo(Status.UP);
            assertThat(flowCalls.get()).isGreaterThanOrEqualTo(2);
        });
        rules.close(); FlowRuleManager.loadRules(List.of());
        GovernanceRules restarted = new GovernanceRules(config, new ObjectMapper(), "demo", "KNODE_SENTINEL", 30, 100);
        try {
            restarted.run(null);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(restarted.health().getStatus()).isEqualTo(Status.UP);
                assertThat(FlowRuleManager.getRules()).hasSize(1);
            });
        } finally { restarted.close(); }
    }
}
