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
        Map<String,Queue<Optional<String>>> stalePrimary=new HashMap<>(),staleOther=new HashMap<>();
        when(config.getConfig(anyString(),anyString(),anyLong())).thenAnswer(i -> {
            String id=i.getArgument(0);var stale=stalePrimary.get(id);
            return stale!=null && !stale.isEmpty()?stale.remove().orElse(null):primary.get(id);
        });
        when(otherConfig.getConfig(anyString(),anyString(),anyLong())).thenAnswer(i -> {
            String id=i.getArgument(0);var stale=staleOther.get(id);
            return stale!=null && !stale.isEmpty()?stale.remove().orElse(null):other.get(id);
        });
        var saves=new AtomicInteger();var mode=new ExternalNacosMode(input,Path.of("target","fixture-cache"),saves::incrementAndGet,500,1);
        ReflectionTestUtils.setField(mode,"config",config);ReflectionTestUtils.setField(mode,"otherConfig",otherConfig);
        when(config.publishConfig(anyString(),anyString(),anyString())).thenAnswer(i -> {
            var changes=(List<?>)mode.manifest().get("changes");
            var latest=(Map<?,?>)changes.get(changes.size()-1);
            assertEquals("planned",latest.get("state"));assertTrue(saves.get()>0);
            String id=i.getArgument(0);String oldValue=primary.put(id,i.getArgument(2));
            stalePrimary.put(id,new LinkedList<>(List.of(Optional.ofNullable(oldValue),Optional.ofNullable(oldValue))));return true;
        });
        when(config.removeConfig(anyString(),anyString())).thenAnswer(i -> {
            var changes=(List<?>)mode.manifest().get("changes");
            var latest=(Map<?,?>)changes.get(changes.size()-1);
            assertEquals("remove",latest.get("action"));assertEquals("planned",latest.get("state"));
            String id=i.getArgument(0);String oldValue=primary.remove(id);
            stalePrimary.put(id,new LinkedList<>(List.of(Optional.ofNullable(oldValue),Optional.ofNullable(oldValue))));return true;
        });
        when(otherConfig.publishConfig(anyString(),anyString(),anyString())).thenAnswer(i -> {
            var changes=(List<?>)mode.manifest().get("changes");
            assertEquals("planned",((Map<?,?>)changes.get(changes.size()-1)).get("state"));
            String id=i.getArgument(0);String oldValue=other.put(id,i.getArgument(2));
            staleOther.put(id,new LinkedList<>(List.of(Optional.ofNullable(oldValue),Optional.ofNullable(oldValue))));return true;
        });
        mode.preflight();
        mode.removeFlow();assertNull(primary.get(ExternalNacosMode.FLOW));
        mode.publish(ExternalNacosMode.FLOW,"[]");assertEquals("[]",primary.get(ExternalNacosMode.FLOW));
        primary.remove(ExternalNacosMode.FLOW);
        assertThrows(IllegalStateException.class,() -> mode.publish(ExternalNacosMode.FLOW,"[]"));
        primary.put(ExternalNacosMode.FLOW,"[]");
        mode.removeFlow();mode.restore();assertEquals("[]",primary.get(ExternalNacosMode.FLOW));
        mode.publish(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":2}");
        stalePrimary.put(ExternalNacosMode.RELAY,new LinkedList<>(List.of(
                Optional.of("{\"sessionEventRelayBatchSize\":50}"),Optional.of("{\"sessionEventRelayBatchSize\":50}"))));
        mode.publishOtherInvalidRelay();
        staleOther.put(ExternalNacosMode.RELAY,new LinkedList<>(List.of(
                Optional.of("{\"sessionEventRelayBatchSize\":19}"),Optional.of("{\"sessionEventRelayBatchSize\":19}"))));
        verify(otherConfig,times(1)).publishConfig(anyString(),anyString(),anyString());
        mode.publish(ExternalNacosMode.FLOW,ExternalNacosMode.RULE);
        mode.restore();
        verify(otherConfig,times(2)).publishConfig(anyString(),anyString(),anyString());
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
    @Test void readbackTimeoutIsJournaledAndCleanupCannotClaimAnUnsettledWriteWasRestored() throws Exception {
        var input=new ExternalNacosGuard.Inputs("127.0.0.1:18848","verify-one","verify-two",
                "VERIFY_DISC","VERIFY_CONF","VERIFY_GOV","fixture-user","fixture-password");
        var client=mock(ConfigService.class);
        String original="{\"sessionEventRelayBatchSize\":50}";
        when(client.getConfig(anyString(),anyString(),anyLong())).thenReturn(original);
        when(client.publishConfig(anyString(),anyString(),anyString())).thenReturn(true);
        var mode=new ExternalNacosMode(input,Path.of("target","fixture-cache"),() -> {},30,1);
        ReflectionTestUtils.setField(mode,"config",client);
        @SuppressWarnings("unchecked")
        var values=(Map<String,String>)ReflectionTestUtils.getField(mode,"original");
        values.put(ExternalNacosMode.RELAY,original);
        assertThrows(IllegalStateException.class,() -> mode.publish(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":2}"));
        assertThrows(IllegalStateException.class,mode::restore);
        verify(client,times(1)).publishConfig(anyString(),anyString(),anyString());
        String journal=new ObjectMapper().writeValueAsString(mode.manifest());
        assertTrue(journal.contains("readback-timeout"));
        assertFalse(journal.contains("\"action\":\"restore\""));
    }

    @Test void unexpectedReadbackIsRejectedWithoutPublishingAgainOrRecordingItsContents() throws Exception {
        var input=new ExternalNacosGuard.Inputs("127.0.0.1:18848","verify-one","verify-two",
                "VERIFY_DISC","VERIFY_CONF","VERIFY_GOV","fixture-user","fixture-password");
        var client=mock(ConfigService.class);
        String original="{\"sessionEventRelayBatchSize\":50}";
        when(client.getConfig(anyString(),anyString(),anyLong())).thenReturn(original,"unexpected-secret-content");
        when(client.publishConfig(anyString(),anyString(),anyString())).thenReturn(true);
        var mode=new ExternalNacosMode(input,Path.of("target","fixture-cache"),() -> {},100,1);
        ReflectionTestUtils.setField(mode,"config",client);
        @SuppressWarnings("unchecked")
        var values=(Map<String,String>)ReflectionTestUtils.getField(mode,"original");
        values.put(ExternalNacosMode.RELAY,original);
        assertThrows(IllegalStateException.class,() -> mode.publish(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":2}"));
        verify(client,times(1)).publishConfig(anyString(),anyString(),anyString());
        String journal=new ObjectMapper().writeValueAsString(mode.manifest());
        assertTrue(journal.contains("readback-unexpected"));
        assertFalse(journal.contains("unexpected-secret-content"));
    }

}
