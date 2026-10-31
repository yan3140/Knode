package com.knode.user.auth;

import com.knode.user.admin.AdminSupport;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once delivery: only a confirmed XADD followed by DB acknowledgement is PUBLISHED. */
@Service
public class SessionEventRelay {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SessionEventTransport transport;
    private final Clock clock;
    private final int maxAttempts,retentionDays;
    public SessionEventRelay(JdbcTemplate db,TransactionTemplate tx,SessionEventTransport transport,Clock clock,Environment env) {
        this.db=db;this.tx=tx;this.transport=transport;this.clock=clock;
        maxAttempts=Integer.parseInt(env.getProperty("KNODE_SESSION_EVENTS_MAX_ATTEMPTS","5"));
        retentionDays=Integer.parseInt(env.getProperty("KNODE_SESSION_EVENTS_RETENTION_DAYS","30"));
        if (maxAttempts<1 || maxAttempts>100 || retentionDays<7 || retentionDays>3650)
            throw new IllegalArgumentException("Invalid session event retry or retention configuration");
    }
    public boolean enabled() {return transport.enabled();}
    public boolean probe() {return transport.probe();}
    private Timestamp now() {return Timestamp.from(clock.instant());}
    public int relay(int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("Invalid relay batch");
        if (!enabled()) return 0;
        Timestamp now=now();
        var ids=db.queryForList("SELECT event_id FROM auth_revocation_outbox WHERE (state='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?)) OR (state='CLAIMED' AND (claim_until IS NULL OR claim_until<=?)) ORDER BY occurred_at,event_id LIMIT ?",String.class,now,now,limit);
        int published=0;
        for (String id:ids) {
            String token=UUID.randomUUID().toString();
            try {
                int exhausted=db.update("UPDATE auth_revocation_outbox SET state='DEAD',claim_token=NULL,claim_until=NULL,last_error='LEASE_EXPIRED' WHERE event_id=? AND state='CLAIMED' AND (claim_until IS NULL OR claim_until<=?) AND attempts>=?",id,now,maxAttempts);
                if (exhausted==1) continue;
                int claimed=db.update("UPDATE auth_revocation_outbox SET state='CLAIMED',claim_token=?,claim_until=?,attempts=attempts+1 WHERE event_id=? AND ((state='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?)) OR (state='CLAIMED' AND (claim_until IS NULL OR claim_until<=?)))",
                        token,Timestamp.from(clock.instant().plusSeconds(30)),id,now,now);
                if (claimed!=1) continue;
                String envelope=materialize(id,token);
                if (envelope==null) continue;
                transport.publish(envelope);
                int ack=db.update("UPDATE auth_revocation_outbox SET state='PUBLISHED',published_at=?,claim_token=NULL,claim_until=NULL,next_attempt_at=NULL,last_error=NULL WHERE event_id=? AND state='CLAIMED' AND claim_token=?",
                        now(),id,token);
                if (ack==1) published++;
            } catch (RuntimeException failure) {
                try {recordFailure(id,token);} catch (RuntimeException ignored) { /* Expired lease will be recovered. */ }
            }
        }
        return published;
    }
    private String materialize(String id,String token) {
        return tx.execute(status -> {
            var rows=db.queryForList("SELECT event_id,sid,user_id,scope,reason,occurred_at,event_json FROM auth_revocation_outbox WHERE event_id=? AND state='CLAIMED' AND claim_token=? FOR UPDATE",id,token);
            if (rows.isEmpty()) return null;
            var row=rows.get(0);String json=(String)row.get("event_json");
            if (json==null) {
                json=SessionEventEnvelope.encode((String)row.get("event_id"),(String)row.get("sid"),
                        ((Number)row.get("user_id")).longValue(),(String)row.get("scope"),(String)row.get("reason"),
                        ((Timestamp)row.get("occurred_at")).toInstant());
                db.update("UPDATE auth_revocation_outbox SET event_json=? WHERE event_id=? AND claim_token=? AND event_json IS NULL",json,id,token);
            }
            return json;
        });
    }
    private void recordFailure(String id,String token) {
        var attempts=db.queryForList("SELECT attempts FROM auth_revocation_outbox WHERE event_id=? AND state='CLAIMED' AND claim_token=?",Integer.class,id,token);
        if (attempts.isEmpty()) return;
        int n=attempts.get(0);boolean dead=n>=maxAttempts;
        long delay=Math.min(300L,2L << Math.min(Math.max(n-1,0),8));
        db.update("UPDATE auth_revocation_outbox SET state=?,next_attempt_at=?,claim_token=NULL,claim_until=NULL,last_error=? WHERE event_id=? AND state='CLAIMED' AND claim_token=?",
                dead?"DEAD":"PENDING",dead?null:Timestamp.from(clock.instant().plusSeconds(delay)),
                "DELIVERY_UNCONFIRMED",id,token);
    }
    public void replay(String id,long actor) {
        try {if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();}
        catch (RuntimeException e) {throw AdminSupport.invalid();}
        tx.executeWithoutResult(status -> {
            int changed=db.update("UPDATE auth_revocation_outbox SET state='PENDING',attempts=0,next_attempt_at=NULL,claim_token=NULL,claim_until=NULL,last_error=NULL WHERE event_id=? AND state='DEAD'",id);
            if (changed!=1) throw AdminSupport.invalid();
            db.update("INSERT INTO auth_revocation_replay_audit(event_id,actor_id,requested_at) VALUES(?,?,?)",id,actor,now());
        });
    }
    public List<Map<String,Object>> list(int limit) {
        if (limit<1 || limit>100) throw AdminSupport.invalid();
        return db.query("SELECT event_id,state,attempts,last_error,occurred_at,published_at FROM auth_revocation_outbox ORDER BY occurred_at DESC,event_id LIMIT ?",
                (r,n) -> {
                    var row=new LinkedHashMap<String,Object>();row.put("eventId",r.getString(1));row.put("state",r.getString(2));
                    row.put("attempts",r.getInt(3));row.put("lastError",r.getString(4));
                    row.put("occurredAt",r.getTimestamp(5).toInstant().toString());
                    Timestamp published=r.getTimestamp(6);row.put("publishedAt",published==null?null:published.toInstant().toString());return row;
                },limit);
    }
    public Map<String,Object> metrics() {
        var result=new LinkedHashMap<String,Object>();result.put("enabled",enabled());
        for (String state:List.of("PENDING","CLAIMED","DEAD","PUBLISHED"))
            result.put(state.toLowerCase(Locale.ROOT),db.queryForObject("SELECT COUNT(*) FROM auth_revocation_outbox WHERE state=?",Long.class,state));
        result.put("expiredClaims",db.queryForObject("SELECT COUNT(*) FROM auth_revocation_outbox WHERE state='CLAIMED' AND (claim_until IS NULL OR claim_until<=?)",Long.class,now()));
        result.put("attempts",db.queryForObject("SELECT COALESCE(SUM(attempts),0) FROM auth_revocation_outbox",Long.class));
        Timestamp last=db.queryForObject("SELECT MAX(published_at) FROM auth_revocation_outbox",Timestamp.class);
        result.put("lastPublishedAt",last==null?null:last.toInstant().toString());
        result.put("publishedMeaning","Stream accepted; consumer completion unknown");
        return result;
    }
    /** Delete old published facts or naturally expired session indexes, never incomplete coordination. */
    public int cleanup(int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("Invalid cleanup batch");
        Timestamp cutoff=Timestamp.from(clock.instant().minus(Duration.ofDays(retentionDays)));
        var ids=db.queryForList("SELECT event_id FROM auth_revocation_outbox WHERE state='PUBLISHED' AND published_at<? ORDER BY published_at,event_id LIMIT ?",String.class,cutoff,limit);
        int deleted=0;
        for (String id:ids) {
            Boolean done=tx.execute(status -> {
                var rows=db.queryForList("SELECT sid FROM auth_revocation_outbox WHERE event_id=? AND state='PUBLISHED' AND published_at<? FOR UPDATE",String.class,id,cutoff);
                if (rows.isEmpty()) return false;
                String sid=rows.get(0);
                if (db.queryForObject("SELECT COUNT(*) FROM auth_login_session WHERE sid=? AND active<>0",Integer.class,sid)>0
                        || db.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=? AND (state<>'REVOKED' OR event_id<>?)",Integer.class,sid,id)>0) return false;
                db.update("DELETE FROM auth_login_session WHERE sid=? AND active=0",sid);
                db.update("DELETE FROM auth_revocation_intent WHERE sid=? AND event_id=? AND state='REVOKED'",sid,id);
                db.update("DELETE FROM auth_revocation_outbox WHERE event_id=? AND state='PUBLISHED' AND published_at<?",id,cutoff);
                return true;
            });
            if (Boolean.TRUE.equals(done)) deleted++;
        }
        int remaining=limit-deleted;
        if (remaining>0) {
            var expired=db.queryForList("SELECT sid FROM auth_login_session s WHERE expires_at<? AND NOT EXISTS (SELECT 1 FROM auth_revocation_intent i WHERE i.sid=s.sid) AND NOT EXISTS (SELECT 1 FROM auth_revocation_outbox o WHERE o.sid=s.sid) ORDER BY expires_at,sid LIMIT ?",String.class,cutoff,remaining);
            for (String sid:expired) {
                Boolean done=tx.execute(status -> {
                    var rows=db.queryForList("SELECT sid FROM auth_login_session WHERE sid=? AND expires_at<? FOR UPDATE",String.class,sid,cutoff);
                    if (rows.isEmpty() || db.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=?",Integer.class,sid)>0
                            || db.queryForObject("SELECT COUNT(*) FROM auth_revocation_outbox WHERE sid=?",Integer.class,sid)>0) return false;
                    return db.update("DELETE FROM auth_login_session WHERE sid=? AND expires_at<?",sid,cutoff)==1;
                });
                if (Boolean.TRUE.equals(done)) deleted++;
            }
        }
        return deleted;
    }
    @Scheduled(fixedDelayString="${KNODE_SESSION_EVENTS_POLL_MS:5000}",initialDelayString="${KNODE_SESSION_EVENTS_INITIAL_MS:5000}")
    public void poll() {
        if (!enabled()) return;
        try {relay(50);} catch (RuntimeException ignored) { /* Persisted lease and due time enable later recovery. */ }
    }
    @Scheduled(fixedDelayString="${KNODE_SESSION_EVENTS_CLEANUP_POLL_MS:86400000}",initialDelayString="${KNODE_SESSION_EVENTS_CLEANUP_INITIAL_MS:86400000}")
    public void cleanupPoll() {try {cleanup(100);} catch (RuntimeException ignored) { /* Retry next scheduled pass. */ }}
}
