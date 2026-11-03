package com.knode.user;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalNacosGuardTest {
    private Map<String,String> valid() {
        var m=new HashMap<String,String>();
        m.put("KNODE_IT_NACOS_EXCLUSIVE","confirm");m.put("KNODE_IT_NACOS_ADDR","127.0.0.1:18848");
        m.put("KNODE_IT_NACOS_NAMESPACE","verify-one");m.put("KNODE_IT_NACOS_OTHER_NAMESPACE","verify-two");
        m.put("KNODE_IT_NACOS_DISCOVERY_GROUP","VERIFY_DISCOVERY");m.put("KNODE_IT_NACOS_CONFIG_GROUP","VERIFY_CONFIG");
        m.put("KNODE_IT_NACOS_GOVERNANCE_GROUP","VERIFY_GOV");m.put("KNODE_IT_NACOS_USERNAME","fixture-user");
        m.put("KNODE_IT_NACOS_PASSWORD","fixture-password");return m;
    }
    @Test void requiresExplicitDistinctIsolatedInputsBeforeAnyNetwork() {
        assertDoesNotThrow(() -> ExternalNacosGuard.check(valid()));
        assertFalse(ExternalNacosGuard.check(valid()).toString().contains("fixture-password"));
        for (String address:new String[]{"localhost:18848","127.0.0.1:8848","127.0.0.1:65000","127.0.0.1:18848/path","127.0.0.1:18848?x=y","user@127.0.0.1:18848","192.0.2.1:18848"}) {
            var m=valid();m.put("KNODE_IT_NACOS_ADDR",address);
            assertThrows(IllegalArgumentException.class,() -> ExternalNacosGuard.check(m));
        }
        for (String key:valid().keySet()) {
            var m=valid();m.remove(key);assertThrows(IllegalArgumentException.class,() -> ExternalNacosGuard.check(m),key);
        }
        var same=valid();same.put("KNODE_IT_NACOS_OTHER_NAMESPACE",same.get("KNODE_IT_NACOS_NAMESPACE"));
        assertThrows(IllegalArgumentException.class,() -> ExternalNacosGuard.check(same));
        var group=valid();group.put("KNODE_IT_NACOS_DISCOVERY_GROUP","DEFAULT_GROUP");
        assertThrows(IllegalArgumentException.class,() -> ExternalNacosGuard.check(group));
        var ns=valid();ns.put("KNODE_IT_NACOS_NAMESPACE","public");
        assertThrows(IllegalArgumentException.class,() -> ExternalNacosGuard.check(ns));
    }
    @Test void onlyNonSecretBoundedRelayAndEmptyInitialRulesAreAccepted() throws Exception {
        assertEquals(2,ExternalNacosMode.relayValue("{\"sessionEventRelayBatchSize\":2}"));
        for (String raw:new String[]{"{}", "{\"sessionEventRelayBatchSize\":0}", "{\"sessionEventRelayBatchSize\":101}",
                "{\"sessionEventRelayBatchSize\":2,\"password\":\"no\"}", "{\"sessionEventRelayBatchSize\":2} {}"}) {
            assertThrows(Exception.class,() -> ExternalNacosMode.relayValue(raw));
        }
        assertDoesNotThrow(() -> ExternalNacosMode.emptyRules(" [ ] "));
        assertThrows(Exception.class,() -> ExternalNacosMode.emptyRules("[{}]"));
        assertThrows(Exception.class,() -> ExternalNacosMode.emptyRules("[] []"));
    }
    @Test void zeroQpsRuleActuallyBlocksTheDeclaredUserResource() {
        String previous=System.getProperty("csp.sentinel.log.dir");
        System.setProperty("csp.sentinel.log.dir",java.nio.file.Path.of("target","g11-sentinel-test").toAbsolutePath().toString());
        String resource="HTTP GET /api/blog/user/userInfo";
        var rule=new com.alibaba.csp.sentinel.slots.block.flow.FlowRule(resource);
        rule.setGrade(1);rule.setCount(0);
        try {
            com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager.loadRules(java.util.List.of(rule));
            assertThrows(com.alibaba.csp.sentinel.slots.block.BlockException.class,() -> {
                var entry=com.alibaba.csp.sentinel.SphU.entry(resource);entry.exit();
            });
        } finally {
            com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager.loadRules(java.util.List.of());
            if (previous==null) System.clearProperty("csp.sentinel.log.dir"); else System.setProperty("csp.sentinel.log.dir",previous);
        }
    }

}
