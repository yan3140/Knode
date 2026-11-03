package com.knode.user;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalNacosModeTest {
    @Test void journalsBeforePublicationAndRestoresOnlyKnownValuesAcrossBothNamespaces() throws Exception {
        var input=new ExternalNacosGuard.Inputs("127.0.0.1:18848","verify-one","verify-two",
                "VERIFY_DISC","VERIFY_CONF","VERIFY_GOV","fixture-user","fixture-password");
        var primary=new HashMap<String,String>();
        primary.put("knode-gateway.yaml",Files.readString(Path.of("..","deploy","microservices","nacos","knode-gateway.yaml")));
        for (String id:List.of("knode-gateway.gw-flow.json","knode-gateway.gw-api-group.json",
                ExternalNacosMode.FLOW,"knode-user.degrade.json")) primary.put(id,"[]");
        primary.put(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":50}");
        var other=new HashMap<String,String>();
        other.put(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":19}");
        var config=mock(ConfigService.class);var otherConfig=mock(ConfigService.class);
        when(config.getConfig(anyString(),anyString(),anyLong())).thenAnswer(i -> primary.get(i.getArgument(0)));
        when(otherConfig.getConfig(anyString(),anyString(),anyLong())).thenAnswer(i -> other.get(i.getArgument(0)));
        var saves=new AtomicInteger();var mode=new ExternalNacosMode(input,Path.of("target","fixture-cache"),saves::incrementAndGet);
        ReflectionTestUtils.setField(mode,"config",config);ReflectionTestUtils.setField(mode,"otherConfig",otherConfig);
        when(config.publishConfig(anyString(),anyString(),anyString())).thenAnswer(i -> {
            var changes=(List<?>)mode.manifest().get("changes");
            var latest=(Map<?,?>)changes.get(changes.size()-1);
            assertEquals("planned",latest.get("state"));assertTrue(saves.get()>0);
            primary.put(i.getArgument(0),i.getArgument(2));return true;
        });
        when(config.removeConfig(anyString(),anyString())).thenAnswer(i -> {
            var changes=(List<?>)mode.manifest().get("changes");
            var latest=(Map<?,?>)changes.get(changes.size()-1);
            assertEquals("remove",latest.get("action"));assertEquals("planned",latest.get("state"));
            primary.remove(i.getArgument(0));return true;
        });
        when(otherConfig.publishConfig(anyString(),anyString(),anyString())).thenAnswer(i -> {
            var changes=(List<?>)mode.manifest().get("changes");
            assertEquals("planned",((Map<?,?>)changes.get(changes.size()-1)).get("state"));
            other.put(i.getArgument(0),i.getArgument(2));return true;
        });
        mode.preflight();
        mode.removeFlow();assertNull(primary.get(ExternalNacosMode.FLOW));
        mode.publish(ExternalNacosMode.FLOW,"[]");assertEquals("[]",primary.get(ExternalNacosMode.FLOW));
        primary.remove(ExternalNacosMode.FLOW);
        assertThrows(IllegalStateException.class,() -> mode.publish(ExternalNacosMode.FLOW,"[]"));
        primary.put(ExternalNacosMode.FLOW,"[]");
        mode.removeFlow();mode.restore();assertEquals("[]",primary.get(ExternalNacosMode.FLOW));
        mode.publish(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":2}");
        mode.publishOtherInvalidRelay();
        mode.publish(ExternalNacosMode.FLOW,ExternalNacosMode.RULE);
        mode.restore();
        assertEquals("{\"sessionEventRelayBatchSize\":50}",primary.get(ExternalNacosMode.RELAY));
        assertEquals("[]",primary.get(ExternalNacosMode.FLOW));
        assertEquals("{\"sessionEventRelayBatchSize\":19}",other.get(ExternalNacosMode.RELAY));

        mode.publish(ExternalNacosMode.FLOW,ExternalNacosMode.RULE);
        mode.publishOtherInvalidRelay();
        primary.put(ExternalNacosMode.FLOW,"unexpected-secret-content");
        assertThrows(IllegalStateException.class,mode::restore);
        assertEquals("unexpected-secret-content",primary.get(ExternalNacosMode.FLOW));
        assertEquals("{\"sessionEventRelayBatchSize\":19}",other.get(ExternalNacosMode.RELAY));
        String manifest=new ObjectMapper().writeValueAsString(mode.manifest());
        assertFalse(manifest.contains("fixture-password"));
        assertFalse(manifest.contains("unexpected-secret-content"));
    }
}
