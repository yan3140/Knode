package com.knode.user.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

public final class SessionEventEnvelope {
    private static final ObjectMapper JSON=new ObjectMapper();
    private SessionEventEnvelope() {}
    public static String encode(String eventId,String sid,long userId,String scope,String reason,Instant occurredAt) {
        UUID.fromString(eventId);UUID.fromString(sid);
        if (userId<1 || !Set.of("blog","admin").contains(scope)
                || !Set.of("LOGOUT","ACCOUNT_SECURITY_CHANGED","UNSPECIFIED").contains(reason) || occurredAt==null)
            throw new IllegalArgumentException("Invalid session event fact");
        var payload=new LinkedHashMap<String,Object>();
        payload.put("sid",sid);payload.put("userId",Long.toString(userId));payload.put("scope",scope);
        payload.put("reason",reason);payload.put("executionScope","SID_CURRENT_EXECUTIONS");
        var event=new LinkedHashMap<String,Object>();
        event.put("eventId",eventId);event.put("eventType","SessionRevoked");event.put("schemaVersion",1);
        event.put("producer","knode-user");event.put("aggregateType","AUTH_SESSION");event.put("aggregateId",sid);
        event.put("sourceRevision","1");event.put("occurredAt",occurredAt.toString());event.put("visibility","INTERNAL");event.put("payload",payload);
        try {return JSON.writeValueAsString(event);} catch (Exception e) {throw new IllegalStateException("Cannot serialize session event");}
    }
}
