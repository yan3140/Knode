package com.knode.user.auth;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Intent commits before Redis revoke. REVOKED is a durable local fact, not yet a published event. */
@Service
public class RevocationCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore.Writer writer;
    public RevocationCoordinator(JdbcTemplate jdbc,TransactionTemplate tx,SessionStore.Writer writer) {
        this.jdbc=jdbc;this.tx=tx;this.writer=writer;
    }
    public void revoke(UserTokens.Principal principal) {
        long userId=Long.parseLong(principal.userId());
        tx.executeWithoutResult(status -> {
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=?",Integer.class,principal.sid());
            if (count==0) jdbc.update("INSERT INTO auth_revocation_intent(sid,user_id,scope,event_id,state) VALUES (?,?,?,?,'PENDING')",
                    principal.sid(),userId,principal.scope(),UUID.randomUUID().toString());
        });
        complete(principal.sid());
    }
    private void complete(String sid) {
        try { writer.revoke(sid); }
        catch (SessionStore.Unavailable e) { throw new AuthenticationFailure(503); }
        tx.executeWithoutResult(status -> jdbc.update("UPDATE auth_revocation_intent SET state='REVOKED',revoked_at=CURRENT_TIMESTAMP WHERE sid=? AND state='PENDING'",sid));
    }
    public boolean pending(String sid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=? AND state='PENDING'",Integer.class,sid)>0;
    }
    public int recoverPending(int limit) {
        if (limit<1 || limit>1000) throw new IllegalArgumentException("limit");
        var sids=jdbc.queryForList("SELECT sid FROM auth_revocation_intent WHERE state='PENDING' ORDER BY created_at LIMIT ?",String.class,limit);
        int completed=0;
        for (String sid:sids) { complete(sid); completed++; }
        return completed;
    }
}
