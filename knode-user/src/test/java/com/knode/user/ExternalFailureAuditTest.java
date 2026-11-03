package com.knode.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalFailureAuditTest {
    @Test void primaryFailureRemainsPrimaryAndCleanupDetailsAreSanitized() throws Exception {
        var audit=new ExternalFailureAudit();
        var original=new IllegalArgumentException("token-or-response-secret");
        var primary=audit.capturePrimary(original,"other-namespace-isolation");
        audit.cleanupFailure("nacos-restore","other-namespace-isolation",new IOException("password-or-response-secret"));
        audit.finish(primary);
        assertEquals(1,primary.getSuppressed().length);
        assertEquals("java.lang.IllegalArgumentException",audit.primary().get("type"));
        assertEquals("other-namespace-isolation",audit.primary().get("stage"));
        assertEquals("ExternalFailureAuditTest",((String)audit.primary().get("class")).replace("com.knode.user.",""));
        assertEquals("nacos-restore",audit.cleanup().get(0).get("step"));
        String manifest=new ObjectMapper().writeValueAsString(java.util.Map.of("primary",audit.primary(),"cleanup",audit.cleanup()));
        assertFalse(manifest.contains("secret"));assertFalse(primary.toString().contains("secret"));
        assertFalse(primary.getSuppressed()[0].toString().contains("secret"));
    }
    @Test void cleanupOnlyFailureIsNotSilentlySwallowed() {
        var audit=new ExternalFailureAudit();
        audit.cleanupFailure("redis-close","cleanup",new IOException("private detail"));
        var error=assertThrows(IllegalStateException.class,() -> audit.finish(null));
        assertEquals(1,error.getSuppressed().length);
        assertFalse(error.toString().contains("private detail"));
    }
    @Test void finallyCleanupFailureCannotReplaceTheOriginalVerificationFailure() {
        var audit=new ExternalFailureAudit();
        var thrown=assertThrows(AssertionError.class,() -> {
            AssertionError primary=null;
            try {throw new IllegalArgumentException("secret-response-body");}
            catch (Throwable original) {
                primary=audit.capturePrimary(original,"other-namespace-isolation");
                throw primary;
            } finally {
                audit.cleanupFailure("nacos-close","other-namespace-isolation",new AssertionError("secret-password"));
                audit.finish(primary);
            }
        });
        assertTrue(thrown.getMessage().contains("java.lang.IllegalArgumentException"));
        assertTrue(thrown.getMessage().contains("other-namespace-isolation"));
        assertFalse(thrown.getMessage().contains("secret"));
        assertEquals(1,thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("Cleanup failed"));
    }

}
