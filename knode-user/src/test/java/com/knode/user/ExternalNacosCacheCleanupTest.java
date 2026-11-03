package com.knode.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalNacosCacheCleanupTest {
    @TempDir Path target;
    @Test void ownedCacheRemovalAndDeferredIoAreExplicitAndSanitized() throws Exception {
        Path cache=Files.createTempDirectory(target,"external-nacos-cache-");
        Path file=Files.writeString(cache.resolve("sdk.log"),"fixture");
        var pending=ExternalNacosCacheCleanup.attempt(target,cache,path -> {
            if (path.equals(file)) throw new FileSystemException(path.toString(),null,"private error detail");
            Files.deleteIfExists(path);
        });
        assertEquals("pending-post-fork",pending.get("state"));
        assertEquals(true,pending.get("postForkCleanupRequired"));
        assertTrue(Files.exists(file));
        assertFalse(new ObjectMapper().writeValueAsString(pending).contains("private error detail"));
        var removed=ExternalNacosCacheCleanup.attempt(target,cache);
        assertEquals("removed-in-fork",removed.get("state"));
        assertEquals(false,removed.get("postForkCleanupRequired"));
        assertFalse(Files.exists(cache));
    }
    @Test void refusesPathsOutsideTheOwnedCacheDirectory() throws Exception {
        Path unrelated=Files.createDirectory(target.resolve("unrelated"));
        assertThrows(IllegalArgumentException.class,() -> ExternalNacosCacheCleanup.attempt(target,unrelated));
        assertTrue(Files.exists(unrelated));
    }
}
