package com.knode.user;

import com.fasterxml.jackson.databind.*;
import com.knode.auth.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Two HTTP authorities backed by their own isolated relational facts, not a production fallback. */
final class LocalDelegationAuthorityFixture implements AutoCloseable {
    private final ObjectMapper json=new ObjectMapper();
    private final JdbcTemplate db;
    private final HttpServer server;
    private final ExecutorService workers=Executors.newFixedThreadPool(4);
    private final ServiceTokenVerifier verifier;
    private final Clock clock;
    final AtomicReference<String> mode=new AtomicReference<>("NORMAL");
    final AtomicInteger authenticatedRequests=new AtomicInteger();
    final AtomicReference<String> lastTraceparent=new AtomicReference<>();
    final AtomicReference<String> lastTracestate=new AtomicReference<>();
    final AtomicReference<String> lastBaggage=new AtomicReference<>();
    final AtomicReference<Runnable> afterSafeProof=new AtomicReference<>();

    LocalDelegationAuthorityFixture(RSAPublicKey publicKey,Clock clock) throws Exception {
        this.clock=clock;
        var ds=new DriverManagerDataSource("jdbc:h2:mem:delegation_authorities;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE IF NOT EXISTS authority_fact (ref VARCHAR(64), authority VARCHAR(64), kind VARCHAR(64), user_id VARCHAR(19), sid VARCHAR(36), user_scope VARCHAR(5), actor VARCHAR(64), resource_json VARCHAR(8192), envelope_json VARCHAR(8192), parent_id VARCHAR(36), root_id VARCHAR(36), revision VARCHAR(19), valid_until VARCHAR(40), authorized_until VARCHAR(40), status VARCHAR(16), PRIMARY KEY(ref,authority))");
        db.execute("CREATE TABLE IF NOT EXISTS authority_run (run_id VARCHAR(64) PRIMARY KEY, root_id VARCHAR(36), control_epoch VARCHAR(19), worker_id VARCHAR(64), step_id VARCHAR(64), operation_id VARCHAR(64), step_started VARCHAR(40), step_until VARCHAR(40), lease_until VARCHAR(40), step_state VARCHAR(16), lease_state VARCHAR(16))");
        verifier=new ServiceTokenVerifier(Map.of("test-key",publicKey),clock);
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.setExecutor(workers);server.createContext("/internal/v1/authz/delegation-proofs:resolve",this::handle);server.start();
    }
    String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    void clear(){db.update("DELETE FROM authority_fact");db.update("DELETE FROM authority_run");mode.set("NORMAL");authenticatedRequests.set(0);afterSafeProof.set(null);lastTraceparent.set(null);lastTracestate.set(null);lastBaggage.set(null);}
    void put(String ref,String authority,String kind,String user,String sid,String scope,String actor,Map<String,String> resource,
             Map<String,List<String>> envelope,String parent,String root,String revision,Instant valid,Instant authorized) throws Exception {
        db.update("INSERT INTO authority_fact(ref,authority,kind,user_id,sid,user_scope,actor,resource_json,envelope_json,parent_id,root_id,revision,valid_until,authorized_until,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE')",
                ref,authority,kind,user,sid,scope,actor,json.writeValueAsString(resource),json.writeValueAsString(envelope),parent,root,revision,valid.toString(),authorized.toString());
    }
    void registerRun(String run,String root,String epoch,String worker) {
        db.update("INSERT INTO authority_run(run_id,root_id,control_epoch,worker_id) VALUES(?,?,?,?)",run,root,epoch,worker);
    }
    void advanceRun(String run,String epoch,String worker) {
        db.update("UPDATE authority_run SET control_epoch=?, worker_id=?, step_id=NULL, operation_id=NULL, step_started=NULL, step_until=NULL, lease_until=NULL, step_state=NULL, lease_state=NULL WHERE run_id=?",epoch,worker,run);
    }
    void rebindRoot(String run,String root) {
        db.update("UPDATE authority_run SET root_id=? WHERE run_id=?",root,run);
    }
    void registerCheckpoint(String run,String step,String operation) {
        registerCheckpoint(run,step,operation,clock.instant().minusSeconds(1),clock.instant().plusSeconds(300),clock.instant().plusSeconds(300));
    }
    void registerCheckpoint(String run,String step,String operation,Instant started,Instant stepUntil,Instant leaseUntil) {
        db.update("UPDATE authority_run SET step_id=?,operation_id=?,step_started=?,step_until=?,lease_until=?,step_state='STARTED',lease_state='ACTIVE' WHERE run_id=?",
                step,operation,started.toString(),stepUntil.toString(),leaseUntil.toString(),run);
    }
    void revokeLease(String run) {db.update("UPDATE authority_run SET lease_state='REVOKED' WHERE run_id=?",run);}
    void revokeStep(String run) {db.update("UPDATE authority_run SET step_state='REVOKED' WHERE run_id=?",run);}
    void updateWindow(String ref,String authority,Instant valid,Instant authorized) {
        db.update("UPDATE authority_fact SET valid_until=?,authorized_until=? WHERE ref=? AND authority=?",
                valid.toString(),authorized.toString(),ref,authority);
    }
    void updateRevision(String ref,String authority,String revision) {db.update("UPDATE authority_fact SET revision=? WHERE ref=? AND authority=?",revision,ref,authority);}
    void updateResource(String ref,String authority,Map<String,String> resource) throws Exception {db.update("UPDATE authority_fact SET resource_json=? WHERE ref=? AND authority=?",json.writeValueAsString(resource),ref,authority);}
    private void handle(HttpExchange exchange) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {send(exchange,405,new byte[0]);return;}
            var h=exchange.getRequestHeaders().get("Authorization");
            if (h==null || h.size()!=1 || !h.get(0).startsWith("Bearer ")) {send(exchange,401,new byte[0]);return;}
            String token=h.get(0).substring(7),authority;
            ServiceTokenVerifier.Principal caller;
            try {caller=verifier.verify(token,"knode-knowledge",Set.of("auth:proofs:read"));authority="knode-knowledge";}
            catch (AuthFailure first) {
                try {caller=verifier.verify(token,"knode-agent",Set.of("auth:proofs:read"));authority="knode-agent";}
                catch (AuthFailure second) {send(exchange,401,new byte[0]);return;}
            }
            if (!"knode-user".equals(caller.service())) {send(exchange,403,new byte[0]);return;}
            authenticatedRequests.incrementAndGet();
            lastTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            lastTracestate.set(exchange.getRequestHeaders().getFirst("tracestate"));
            lastBaggage.set(exchange.getRequestHeaders().getFirst("baggage"));
            String m=mode.get();
            if ("TIMEOUT".equals(m)) {Thread.sleep(3500);send(exchange,503,new byte[0]);return;}
            if ("TRICKLE".equals(m)) {
                exchange.sendResponseHeaders(200,0);
                try (var out=exchange.getResponseBody()) {
                    out.write('{');out.flush();Thread.sleep(3500);out.write('}');
                }
                return;
            }
            if ("OVERSIZE".equals(m)) {send(exchange,200,"x".repeat(17000).getBytes(StandardCharsets.UTF_8));return;}
            if ("DENIED".equals(m)) {send(exchange,403,new byte[0]);return;}
            if ("REDIRECT".equals(m)) {exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/");send(exchange,302,new byte[0]);return;}
            byte[] input=exchange.getRequestBody().readNBytes(8193);
            if (input.length>8192) {send(exchange,400,new byte[0]);return;}
            JsonNode request=json.readTree(input);String ref=request.path("proofRef").asText();
            var facts=db.queryForList("SELECT * FROM authority_fact WHERE ref=? AND authority=?",ref,authority);
            if (facts.size()!=1) {send(exchange,403,new byte[0]);return;}
            var f=facts.get(0);var subject=request.path("subject");
            if (!Objects.equals(request.path("proofKind").asText(),f.get("kind"))
                    || !Objects.equals(request.path("actorService").asText(),f.get("actor"))
                    || !Objects.equals(subject.path("userId").asText(),f.get("user_id"))
                    || !Objects.equals(subject.path("sid").asText(),f.get("sid"))
                    || !Objects.equals(subject.path("userScope").asText(),f.get("user_scope"))
                    || !Objects.equals(nullable(request,"parentGrantId"),f.get("parent_id"))
                    || !Objects.equals(nullable(request,"rootGrantId"),f.get("root_id"))) {send(exchange,403,new byte[0]);return;}
            JsonNode resource=json.readTree((String)f.get("resource_json"));
            Instant proofUntil=Instant.parse((String)f.get("valid_until"));
            if ("knode-agent".equals(authority)) {
                var runs=db.queryForList("SELECT * FROM authority_run WHERE run_id=?",resource.path("runId").asText());
                if (runs.size()!=1) {send(exchange,403,new byte[0]);return;}
                var run=runs.get(0);
                if ((!"AGENT_CONTINUE".equals(f.get("kind")) && !Objects.equals(run.get("root_id"),f.get("root_id")))
                        || !Objects.equals(run.get("control_epoch"),resource.path("controlEpoch").asText())
                        || !Objects.equals(run.get("worker_id"),resource.path("workerId").asText())) {send(exchange,403,new byte[0]);return;}
                if ("AGENT_SAFE_CHECKPOINT".equals(f.get("kind"))) {
                    if (!Objects.equals(run.get("step_id"),resource.path("stepId").asText())
                            || !Objects.equals(run.get("operation_id"),resource.path("checkpointOperationId").asText())
                            || !"STARTED".equals(run.get("step_state")) || !"ACTIVE".equals(run.get("lease_state"))
                            || run.get("step_started")==null || run.get("step_until")==null || run.get("lease_until")==null) {
                        send(exchange,403,new byte[0]);return;
                    }
                    Instant started=Instant.parse((String)run.get("step_started"));
                    Instant stepUntil=Instant.parse((String)run.get("step_until"));
                    Instant leaseUntil=Instant.parse((String)run.get("lease_until"));
                    if (started.isAfter(clock.instant()) || !stepUntil.isAfter(clock.instant()) || !leaseUntil.isAfter(clock.instant())) {
                        send(exchange,403,new byte[0]);return;
                    }
                    if (stepUntil.isBefore(proofUntil)) proofUntil=stepUntil;
                    if (leaseUntil.isBefore(proofUntil)) proofUntil=leaseUntil;
                }
            }
            var expected=request.path("expectedResource").fields();
            while (expected.hasNext()) {var e=expected.next();if (!e.getValue().equals(resource.get(e.getKey()))) {send(exchange,403,new byte[0]);return;}}
            var out=new LinkedHashMap<String,Object>();
            out.put("schemaVersion","UNKNOWN_SCHEMA".equals(m)?2:1);out.put("proofRef",ref);out.put("authority",authority);
            out.put("proofKind",f.get("kind"));out.put("status",f.get("status"));
            out.put("subject",Map.of("userId",f.get("user_id"),"sid",f.get("sid"),"userScope",f.get("user_scope")));
            out.put("actorService","WRONG_ACTOR".equals(m)?"knode-other":f.get("actor"));
            out.put("resource",resource);out.put("permissionsByAudience",json.readTree((String)f.get("envelope_json")));
            out.put("sourceRevision",f.get("revision"));out.put("validUntil",proofUntil.toString());out.put("authorizationExpiresAt",f.get("authorized_until"));
            out.put("parentGrantId",f.get("parent_id"));out.put("rootGrantId",f.get("root_id"));
            if ("MISSING".equals(m)) out.remove("validUntil");
            if ("AGENT_SAFE_CHECKPOINT".equals(f.get("kind"))) {
                Runnable hook=afterSafeProof.getAndSet(null);
                if (hook!=null) hook.run();
            }
            send(exchange,200,json.writeValueAsBytes(out));
        } catch (Exception e) {try {send(exchange,503,new byte[0]);} catch (Exception ignored) {}}
        finally {exchange.close();}
    }
    private static String nullable(JsonNode n,String name){return n.path(name).isNull() || n.path(name).isMissingNode()?null:n.path(name).asText();}
    private static void send(HttpExchange e,int status,byte[] bytes) throws Exception {
        e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);
        try (var output=e.getResponseBody()) {output.write(bytes);}
    }
    @Override public void close(){server.stop(0);workers.shutdownNow();}
}
