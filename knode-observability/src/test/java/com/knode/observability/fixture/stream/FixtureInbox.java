package com.knode.observability.fixture.stream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knode.observability.EventTraceContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Test-only durable inbox. It stores digests, never the event payload. */
final class FixtureInbox implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> BASE=Set.of("eventId","eventType","schemaVersion","producer","aggregateType",
            "aggregateId","sourceRevision","occurredAt","visibility","payload");
    private static final Set<String> PAYLOAD=Set.of("sid","userId","scope","reason","executionScope");
    private final Connection db;
    private final EventTraceContext traces;
    FixtureInbox(String jdbc, OpenTelemetry telemetry) throws SQLException {
        db=DriverManager.getConnection(jdbc); db.setAutoCommit(false); traces=new EventTraceContext(telemetry);
        try(Statement s=db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS inbox (event_hash VARCHAR(64) PRIMARY KEY, fact_hash VARCHAR(64) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS delivery (record_hash VARCHAR(64) PRIMARY KEY, attempts INT NOT NULL, status VARCHAR(20) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS conflicts (record_hash VARCHAR(64) PRIMARY KEY, event_hash VARCHAR(64) NOT NULL)");
        }
        db.commit();
    }
    record Result(String status, int attempt, String traceId) { boolean acknowledge(){return status.equals("APPLIED") || status.equals("DUPLICATE");} }
    Result process(String recordId, Map<String,String> fields) throws Exception {
        String recordHash=hash(recordId);
        Event event=null;
        try { event=parse(fields); } catch (IllegalArgumentException ignored) { /* persist unsupported, no ACK */ }
        int attempt;
        String status;
        try {
            attempt=nextAttempt(recordHash);
            if(event==null) status="UNSUPPORTED";
            else {
                String previous=null;
                try(PreparedStatement q=db.prepareStatement("SELECT fact_hash FROM inbox WHERE event_hash=?")) {
                    q.setString(1,event.eventHash); try(ResultSet rows=q.executeQuery()) {if(rows.next()) previous=rows.getString(1);}
                }
                if(previous==null) {
                    try(PreparedStatement q=db.prepareStatement("INSERT INTO inbox(event_hash,fact_hash) VALUES(?,?)")) {
                        q.setString(1,event.eventHash);q.setString(2,event.factHash);q.executeUpdate();
                    }
                    status="APPLIED";
                } else if(previous.equals(event.factHash)) status="DUPLICATE";
                else {
                    try(PreparedStatement q=db.prepareStatement("MERGE INTO conflicts(record_hash,event_hash) KEY(record_hash) VALUES(?,?)")) {
                        q.setString(1,recordHash);q.setString(2,event.eventHash);q.executeUpdate();
                    }
                    status="CONFLICT";
                }
            }
            try(PreparedStatement q=db.prepareStatement("UPDATE delivery SET status=? WHERE record_hash=?")) {
                q.setString(1,status);q.setString(2,recordHash);q.executeUpdate();
            }
            db.commit();
        } catch(Exception ex) { db.rollback(); throw ex; }
        var saved=event==null ? null : traces.fromStored(event.traceparent,null).orElse(null);
        Span span=traces.startConsumer(saved,"fixture.session_revoked.consume",attempt);
        span.setAttribute("knode.fixture.result",status.toLowerCase(Locale.ROOT)); span.end();
        return new Result(status,attempt,span.getSpanContext().getTraceId());
    }
    private int nextAttempt(String id) throws SQLException {
        int current=0;
        try(PreparedStatement q=db.prepareStatement("SELECT attempts FROM delivery WHERE record_hash=?")) {
            q.setString(1,id);try(ResultSet r=q.executeQuery()){if(r.next())current=r.getInt(1);}
        }
        int next=Math.addExact(current,1);
        try(PreparedStatement q=db.prepareStatement("MERGE INTO delivery(record_hash,attempts,status) KEY(record_hash) VALUES(?,?,'PENDING')")) {
            q.setString(1,id);q.setInt(2,next);q.executeUpdate();
        }
        return next;
    }
    private record Event(String eventHash,String factHash,String traceparent) {}
    private static Event parse(Map<String,String> fields) {
        try {
            if(!fields.keySet().equals(Set.of("event")) || fields.get("event")==null || fields.get("event").length()>16384) throw new IllegalArgumentException();
            JsonNode root=JSON.readTree(fields.get("event"));
            if(root==null || !root.isObject() || (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt())) throw new IllegalArgumentException();
            int v=root.path("schemaVersion").intValue();
            Set<String> allowed=new HashSet<>(BASE); if(v==2) allowed.add("traceContext"); else if(v!=1) throw new IllegalArgumentException();
            Set<String> keys=new HashSet<>();root.fieldNames().forEachRemaining(keys::add);
            if(!keys.containsAll(BASE) || !allowed.containsAll(keys)) throw new IllegalArgumentException();
            require(root,"eventType","SessionRevoked");require(root,"producer","knode-user");
            require(root,"aggregateType","AUTH_SESSION");require(root,"sourceRevision","1");require(root,"visibility","INTERNAL");
            String id=text(root,"eventId"), aggregate=text(root,"aggregateId");
            if(!uuid(id)||!uuid(aggregate)) throw new IllegalArgumentException();
            String time=text(root,"occurredAt");if(!time.endsWith("Z"))throw new IllegalArgumentException();Instant.parse(time);
            JsonNode p=root.path("payload"); if(!p.isObject())throw new IllegalArgumentException();
            Set<String> pk=new HashSet<>();p.fieldNames().forEachRemaining(pk::add);if(!pk.equals(PAYLOAD))throw new IllegalArgumentException();
            if(!aggregate.equals(text(p,"sid")) || !text(p,"userId").matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            if(!Set.of("blog","admin").contains(text(p,"scope")) ||
                    !Set.of("LOGOUT","ACCOUNT_SECURITY_CHANGED","UNSPECIFIED").contains(text(p,"reason"))) throw new IllegalArgumentException();
            require(p,"executionScope","SID_CURRENT_EXECUTIONS");
            String parent=null;
            if(root.has("traceContext")) {
                JsonNode tc=root.get("traceContext");
                if(!tc.isObject() || tc.size()!=1 || !tc.has("traceparent") || !tc.get("traceparent").isTextual()) throw new IllegalArgumentException();
                parent=tc.get("traceparent").textValue();
            }
            ObjectNode fact=((ObjectNode)root).deepCopy();fact.remove("traceContext");
            Object canonical=canonical(fact);
            return new Event(hash(id),hash(JSON.writeValueAsString(canonical)),parent);
        } catch(Exception e){throw new IllegalArgumentException("unsupported event");}
    }
    private static Object canonical(JsonNode n) {
        if(n.isObject()) {Map<String,Object> m=new TreeMap<>();n.fields().forEachRemaining(e->m.put(e.getKey(),canonical(e.getValue())));return m;}
        if(n.isArray()){List<Object> a=new ArrayList<>();n.forEach(v->a.add(canonical(v)));return a;}
        if(n.isTextual())return n.textValue(); if(n.isIntegralNumber())return n.bigIntegerValue();
        if(n.isBoolean())return n.booleanValue();if(n.isNull())return null;throw new IllegalArgumentException();
    }
    private static boolean uuid(String s){return s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static String text(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isTextual())throw new IllegalArgumentException();return v.textValue();}
    private static void require(JsonNode n,String key,String value){if(!value.equals(text(n,key)))throw new IllegalArgumentException();}
    private static String hash(String s) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    long count(String table) throws SQLException {
        if(!Set.of("inbox","delivery","conflicts").contains(table))throw new IllegalArgumentException();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}
    }
    @Override public void close() throws SQLException {db.close();}
}
