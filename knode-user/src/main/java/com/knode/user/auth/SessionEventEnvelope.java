package com.knode.user.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

public final class SessionEventEnvelope {
    private static final ObjectMapper JSON=new ObjectMapper();
    private SessionEventEnvelope() {}
    public static String encode(String eventId,String sid,long userId,String scope,String reason,Instant occurredAt) {
        return encode(eventId,sid,userId,scope,reason,occurredAt,1,null);
    }
    public static String encodeV2(String eventId,String sid,long userId,String scope,String reason,Instant occurredAt,String traceparent) {
        return encode(eventId,sid,userId,scope,reason,occurredAt,2,traceparent);
    }
    private static String encode(String eventId,String sid,long userId,String scope,String reason,Instant occurredAt,int version,String traceparent) {
        if (!UUID.fromString(eventId).toString().equals(eventId) || !UUID.fromString(sid).toString().equals(sid))
            throw new IllegalArgumentException("Non-canonical event identity");
        if (userId<1 || !Set.of("blog","admin").contains(scope)
                || !Set.of("LOGOUT","ACCOUNT_SECURITY_CHANGED","UNSPECIFIED").contains(reason) || occurredAt==null)
            throw new IllegalArgumentException("Invalid session event fact");
        var payload=new LinkedHashMap<String,Object>();
        payload.put("sid",sid);payload.put("userId",Long.toString(userId));payload.put("scope",scope);
        payload.put("reason",reason);payload.put("executionScope","SID_CURRENT_EXECUTIONS");
        var event=new LinkedHashMap<String,Object>();
        event.put("eventId",eventId);event.put("eventType","SessionRevoked");event.put("schemaVersion",version);
        event.put("producer","knode-user");event.put("aggregateType","AUTH_SESSION");event.put("aggregateId",sid);
        event.put("sourceRevision","1");event.put("occurredAt",occurredAt.toString());event.put("visibility","INTERNAL");event.put("payload",payload);
        if (traceparent!=null) {
            var contexts=new com.knode.observability.EventTraceContext(io.opentelemetry.api.OpenTelemetry.noop());
            var saved=contexts.fromStored(traceparent,null).orElseThrow(() -> new IllegalArgumentException("Invalid stored trace context"));
            if (!traceparent.equals(saved.traceparent())) throw new IllegalArgumentException("Non-normalized stored trace context");
            event.put("traceContext",Map.of("traceparent",traceparent));
        }
        try {
            String encoded=JSON.writeValueAsString(event);
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096) throw new IllegalStateException("Session event exceeds budget");
            return encoded;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Cannot serialize session event");}
    }
}
