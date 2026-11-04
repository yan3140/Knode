package com.knode.user;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalBrowserCleanupTest {
    @TempDir Path target;
    @Test void removesOnlyOwnedRuntimeIncludingLongPaths() throws Exception {
        Path owned=Files.createTempDirectory(target,"external-browser-");
        Path sibling=Files.createTempFile(target,"keep-",".txt");
        Path nested=owned;
        for (int i=0;i<18;i++) nested=nested.resolve("long-segment-"+i);
        Files.createDirectories(nested);Files.writeString(nested.resolve("file"),"fixture");
        var result=ExternalBrowserCleanup.attempt(target,owned);
        assertEquals(false,result.get("postForkCleanupRequired"));
        assertFalse(Files.exists(owned));assertTrue(Files.exists(sibling));
    }
    @Test void preservesPendingResultAndRejectsUnownedPaths() throws Exception {
        Path owned=Files.createTempDirectory(target,"external-browser-");
        Files.writeString(owned.resolve("locked"),"fixture");
        var result=ExternalBrowserCleanup.attempt(target,owned,path -> {throw new IOException("simulated lock");});
        assertEquals(true,result.get("postForkCleanupRequired"));
        assertTrue(Files.exists(owned.resolve("locked")));
        Path wrong=Files.createTempDirectory(target,"other-");
        assertThrows(IllegalArgumentException.class,()->ExternalBrowserCleanup.attempt(target,wrong));
        assertTrue(Files.exists(wrong));
    }
}
