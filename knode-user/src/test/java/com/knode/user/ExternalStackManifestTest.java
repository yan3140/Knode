package com.knode.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ExternalStackManifestTest {
    @TempDir Path target;
    @Test void cachePendingIsNotReportedAsCompletedAndForkIdentityIsRecorded() throws Exception {
        var stack=new ExternalStackIT();
        Path manifest=target.resolve("manifest.json");
        ReflectionTestUtils.setField(stack,"manifest",manifest);
        ReflectionTestUtils.invokeMethod(stack,"saveManifest");
        assertFalse(new ObjectMapper().readTree(manifest.toFile()).path("cleanupComplete").asBoolean());
        ReflectionTestUtils.setField(stack,"cleanupFinished",true);
        ReflectionTestUtils.setField(stack,"nacosCacheCleanup",Map.of(
                "ownedPath",target.resolve("external-nacos-cache-123").toString(),
                "state","pending-post-fork","postForkCleanupRequired",true));
        ReflectionTestUtils.invokeMethod(stack,"saveManifest");
        var json=new ObjectMapper().readTree(manifest.toFile());
        assertFalse(json.path("cleanupComplete").asBoolean());
        assertEquals(0,json.path("cleanupFailures").asInt());
        assertEquals(ProcessHandle.current().pid(),json.path("testForkPid").asLong());
        assertEquals("pending-post-fork",json.path("nacosCacheCleanup").path("state").asText());
        assertTrue(json.path("nacosCacheCleanup").path("postForkCleanupRequired").asBoolean());
        ReflectionTestUtils.setField(stack,"nacosCacheCleanup",Map.of(
                "ownedPath",target.resolve("external-nacos-cache-123").toString(),
                "state","removed-in-fork","postForkCleanupRequired",false));
        ReflectionTestUtils.invokeMethod(stack,"saveManifest");
        assertTrue(new ObjectMapper().readTree(manifest.toFile()).path("cleanupComplete").asBoolean());
    }
}
