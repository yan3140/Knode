package com.knode.content.relay;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplayTransaction {
    private final JdbcTemplate db;
    public ReplayTransaction(JdbcTemplate db) { this.db=db; }
    private record Audit(String requestId,String eventId,String operator) {}
    private record Delivery(String state,Timestamp due,String token) {}
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void apply(String eventId,String requestId,String operator) {
        String hash=hash(requestId);
        if (matchesExisting(hash,eventId,requestId,operator)) return;
        List<Delivery> rows=db.query("SELECT state,due_at,lease_token FROM content_event_delivery WHERE event_id=? FOR UPDATE",
                (r,n) -> new Delivery(r.getString(1),r.getTimestamp(2),r.getString(3)),eventId);
        if(rows.isEmpty()) throw new OpsFailure(404);
        // Under READ COMMITTED, a replay accepted while we waited for the delivery row is now visible.
        if (matchesExisting(hash,eventId,requestId,operator)) return;
        Delivery row=rows.get(0);
        Timestamp now=db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
        if("LOCAL_ONLY".equals(row.state())) throw new OpsFailure(409);
        if("LEASED".equals(row.state()) && row.due()!=null && row.due().after(now)) throw new OpsFailure(409);
        if(!List.of("PENDING","LEASED","SENT","DEAD").contains(row.state())) throw new OpsFailure(503);
        if("LEASED".equals(row.state()) && (row.due()==null || row.token()==null ||
                db.update("UPDATE content_event_attempt_audit SET outcome='EXPIRED_UNKNOWN',finished_at=?,error_category='LEASE_EXPIRED' "+
                    "WHERE lease_token=? AND event_id=? AND outcome='LEASED'",now,row.token(),eventId)!=1)) throw new OpsFailure(503);
        db.update("UPDATE content_event_delivery SET state='PENDING',attempts=0,due_at=?,lease_token=NULL,last_error=NULL WHERE event_id=?",now,eventId);
        db.update("INSERT INTO content_event_replay_audit(replay_request_hash,replay_request_id,event_id,operator_service,accepted_at) VALUES(?,?,?,?,?)",
                hash,requestId,eventId,operator,now);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW,readOnly=true,timeout=5)
    public void verifyExisting(String eventId,String requestId,String operator) {
        if (!matchesExisting(hash(requestId),eventId,requestId,operator)) throw new OpsFailure(503);
    }
    private boolean matchesExisting(String hash,String eventId,String requestId,String operator) {
        // The audit is append-only. Its unique key resolves races between different delivery rows.
        List<Audit> rows=db.query("SELECT replay_request_id,event_id,operator_service FROM content_event_replay_audit WHERE replay_request_hash=?",
                (r,n) -> new Audit(r.getString(1),r.getString(2),r.getString(3)),hash);
        if (rows.isEmpty()) return false;
        Audit audit=rows.get(0);
        if (!audit.requestId().equals(requestId) || !audit.eventId().equals(eventId) || !audit.operator().equals(operator))
            throw new OpsFailure(409);
        return true;
    }
    private static String hash(String requestId) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requestId.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception failure) { throw new IllegalStateException("Replay digest unavailable"); }
    }
}
