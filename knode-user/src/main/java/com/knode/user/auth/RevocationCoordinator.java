package com.knode.user.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Redis deletion precedes a transactional local outbox fact; no Stream delivery is implied. */
@Service
public class RevocationCoordinator {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RevocationCoordinator.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore.Writer writer;
    private final Clock clock;
    private final AtomicLong successes=new AtomicLong(), failures=new AtomicLong();
    public RevocationCoordinator(JdbcTemplate jdbc,TransactionTemplate tx,SessionStore.Writer writer,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.writer=writer;this.clock=clock;
    }
    public void revoke(UserTokens.Principal principal) {
        long userId=Long.parseLong(principal.userId());
        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT sid FROM auth_login_session WHERE sid=? FOR UPDATE",String.class,principal.sid());
                jdbc.update("INSERT INTO auth_revocation_intent(sid,user_id,scope,event_id,state) VALUES (?,?,?,?,'PENDING')",
                        principal.sid(),userId,principal.scope(),UUID.randomUUID().toString());
            });
        } catch (DuplicateKeyException ignored) {
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=? AND user_id=? AND scope=?",
                    Integer.class,principal.sid(),userId,principal.scope());
            if (count==null || count!=1) throw new AuthenticationFailure(401);
        }
        complete(principal.sid(),null);
    }
    private boolean complete(String sid,String lease) {
        try { writer.revoke(sid); }
        catch (SessionStore.Unavailable e) {
            recordFailure(sid,lease);
            throw new AuthenticationFailure(503);
        }
        Boolean changed=tx.execute(status -> {
            var rows=jdbc.queryForList("SELECT sid,user_id,scope,event_id,state FROM auth_revocation_intent WHERE sid=? FOR UPDATE",sid);
            if (rows.size()!=1) throw new AuthenticationFailure(503);
            var row=rows.get(0);
            if ("PENDING".equals(row.get("state"))) {
                Timestamp occurred=Timestamp.from(clock.instant());
                jdbc.update("INSERT INTO auth_revocation_outbox(event_id,sid,user_id,scope,occurred_at,execution_scope,state) VALUES(?,?,?,?,?,'SID_CURRENT_EXECUTIONS','PENDING')",
                        row.get("event_id"),sid,row.get("user_id"),row.get("scope"),occurred);
                jdbc.update("UPDATE auth_revocation_intent SET state='REVOKED',revoked_at=?,claim_token=NULL,claim_until=NULL,last_error=NULL WHERE sid=? AND state='PENDING'",occurred,sid);
                jdbc.update("UPDATE auth_login_session SET active=0 WHERE sid=?",sid);
                return true;
            }
            if (!"REVOKED".equals(row.get("state"))) throw new AuthenticationFailure(503);
            return false;
        });
        if (Boolean.TRUE.equals(changed)) successes.incrementAndGet();
        return Boolean.TRUE.equals(changed);
    }
    private void recordFailure(String sid,String lease) {
        failures.incrementAndGet();
        tx.executeWithoutResult(status -> {
            Timestamp next=nextAttempt(sid);
            if (lease==null) {
                jdbc.update("UPDATE auth_revocation_intent SET attempts=attempts+1,next_attempt_at=?,last_error='SESSION_STORE_UNAVAILABLE' WHERE sid=? AND state='PENDING'",next,sid);
            } else {
                jdbc.update("UPDATE auth_revocation_intent SET attempts=attempts+1,next_attempt_at=?,claim_token=NULL,claim_until=NULL,last_error='SESSION_STORE_UNAVAILABLE' WHERE sid=? AND state='PENDING' AND claim_token=?",next,sid,lease);
            }
        });
    }
    private Timestamp nextAttempt(String sid) {
        var rows=jdbc.query("SELECT attempts FROM auth_revocation_intent WHERE sid=?",(r,n)->r.getInt(1),sid);
        int attempts=rows.isEmpty()?0:Math.max(0,rows.get(0));
        long delay=Math.min(300L,2L << Math.min(attempts,8));
        return Timestamp.from(clock.instant().plusSeconds(delay));
    }
    /** Must be called while the account mutation transaction holds its row lock. */
    public void enqueueUser(long userId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Account mutation transaction required");
        var sessions=jdbc.queryForList("SELECT sid,scope FROM auth_login_session WHERE user_id=? AND active=1 FOR UPDATE",userId);
        for (var session:sessions) {
            String sid=(String)session.get("sid"), scope=(String)session.get("scope");
            Integer present=jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=?",Integer.class,sid);
            if (present==0) jdbc.update("INSERT INTO auth_revocation_intent(sid,user_id,scope,event_id,state) VALUES (?,?,?,?,'PENDING')",
                    sid,userId,scope,UUID.randomUUID().toString());
        }
        jdbc.update("UPDATE auth_login_session SET active=0 WHERE user_id=? AND active=1",userId);
    }
    /** Called after commit: return 503 if any authority deletion was not confirmed. */
    public void finishUser(long userId) {
        var sids=jdbc.queryForList("SELECT sid FROM auth_revocation_intent WHERE user_id=? AND state='PENDING'",String.class,userId);
        boolean failed=false;
        for (String sid:sids) {
            try { complete(sid,null); }
            catch (RuntimeException e) { failed=true; }
        }
        if (failed) throw new AuthenticationFailure(503);
    }
    public boolean pending(String sid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=? AND state='PENDING'",Integer.class,sid)>0;
    }
    /** Bounded, leased work; one failed sid cannot prevent attempts for the remainder. */
    public int recoverPending(int limit) {
        if (limit<1 || limit>1000) throw new IllegalArgumentException("limit");
        Timestamp now=Timestamp.from(clock.instant());
        var sids=jdbc.queryForList("SELECT sid FROM auth_revocation_intent WHERE state='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?) AND (claim_until IS NULL OR claim_until<=?) ORDER BY COALESCE(next_attempt_at,created_at),created_at LIMIT ?",String.class,now,now,limit);
        int completed=0;
        for (String sid:sids) {
            String lease=UUID.randomUUID().toString();
            int claimed=jdbc.update("UPDATE auth_revocation_intent SET claim_token=?,claim_until=? WHERE sid=? AND state='PENDING' AND (claim_until IS NULL OR claim_until<=?) AND (next_attempt_at IS NULL OR next_attempt_at<=?)",
                    lease,Timestamp.from(clock.instant().plusSeconds(30)),sid,now,now);
            if (claimed!=1) continue;
            try { if (complete(sid,lease)) completed++; }
            catch (AuthenticationFailure e) { /* Failure already recorded; proceed to next sid. */ }
            catch (RuntimeException e) {
                failures.incrementAndGet();
                try { jdbc.update("UPDATE auth_revocation_intent SET attempts=attempts+1,next_attempt_at=?,claim_token=NULL,claim_until=NULL,last_error='LOCAL_COMPLETION_FAILED' WHERE sid=? AND state='PENDING' AND claim_token=?",
                        nextAttempt(sid),sid,lease); }
                catch (RuntimeException ignored) { /* Lease expires after an unavailable database recovers. */ }
            }
        }
        return completed;
    }
    @Scheduled(fixedDelayString="${KNODE_AUTH_REVOCATION_POLL_MS:5000}",initialDelayString="${KNODE_AUTH_REVOCATION_INITIAL_MS:5000}")
    public void poll() {
        try { recoverPending(100); }
        catch (RuntimeException e) { failures.incrementAndGet(); LOG.warn("Revocation recovery scan unavailable; will retry"); }
    }
    public long successes() { return successes.get(); }
    public long failures() { return failures.get(); }
}
