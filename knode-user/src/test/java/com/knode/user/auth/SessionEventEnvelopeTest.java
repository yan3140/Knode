package com.knode.user.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionEventEnvelopeTest {
    @Test void v2AcceptsOnlyNormalizedTraceparentAndPositiveInt64() throws Exception {
        String id=UUID.randomUUID().toString(),sid=UUID.randomUUID().toString();
        String trace="00-11111111111111111111111111111111-2222222222222222-01";
        String value=SessionEventEnvelope.encodeV2(id,sid,Long.MAX_VALUE,"blog","LOGOUT",Instant.parse("2026-01-01T00:00:00Z"),trace);
        var json=new ObjectMapper().readTree(value);
        assertEquals(2,json.path("schemaVersion").asInt());assertEquals("9223372036854775807",json.path("payload").path("userId").asText());
        assertEquals(trace,json.path("traceContext").path("traceparent").asText());assertEquals(1,json.path("traceContext").size());
        assertTrue(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<4096);
        assertThrows(IllegalArgumentException.class,() -> SessionEventEnvelope.encodeV2(id,sid,0,"blog","LOGOUT",Instant.now(),trace));
        assertThrows(IllegalArgumentException.class,() -> SessionEventEnvelope.encodeV2("1-1-1-1-1",sid,1,"blog","LOGOUT",Instant.now(),trace));
        assertThrows(IllegalArgumentException.class,() -> SessionEventEnvelope.encodeV2(id,"ABCDEF01-1111-4111-8111-111111111111",1,"blog","LOGOUT",Instant.now(),trace));
        assertThrows(IllegalArgumentException.class,() -> SessionEventEnvelope.encodeV2(id,sid,1,"blog","LOGOUT",Instant.now(),"00-"+"0".repeat(32)+"-2222222222222222-01"));
        assertThrows(IllegalArgumentException.class,() -> SessionEventEnvelope.encodeV2(id,sid,1,"blog","LOGOUT",Instant.now(),trace+"x".repeat(512)));
        assertFalse(new ObjectMapper().readTree(SessionEventEnvelope.encodeV2(id,sid,1,"blog","LOGOUT",Instant.now(),null)).has("traceContext"));
    }
}
