package com.knode.content.relay;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelayTransaction {
    public static final int MAX_ATTEMPTS=5;
    private static final long LEASE_SECONDS=30;
    public record Claim(String eventId,String family,String type,String token,int attempt,boolean exhausted) {}
    private record Row(String id,String family,String type,int attempts,long total,String state,String token,Timestamp due) {}
    private final JdbcTemplate db;
    public RelayTransaction(JdbcTemplate db) { this.db=db; }
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Claim claim() {
        Timestamp cutoff=db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
        List<Row> rows=db.query("SELECT event_id,family,event_type,attempts,total_attempts,state,lease_token,due_at FROM content_event_delivery "+
                "WHERE due_at IS NOT NULL AND due_at<=? AND state IN ('PENDING','LEASED') ORDER BY due_at,event_id LIMIT 1 FOR UPDATE",
                (r,n) -> new Row(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getLong(5),r.getString(6),r.getString(7),r.getTimestamp(8)),cutoff);
        if(rows.isEmpty()) return null;
        Row row=rows.get(0);
        // MySQL timestamps are fixed at statement start; start the lease only after acquiring the row lock.
        Timestamp now=db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
        if(row.due()==null || row.due().after(now) || !List.of("PENDING","LEASED").contains(row.state())) return null;
        if("LEASED".equals(row.state())) {
            int audit=row.token()==null?0:db.update("UPDATE content_event_attempt_audit SET outcome='EXPIRED_UNKNOWN',finished_at=?,error_category='LEASE_EXPIRED' "+
                    "WHERE lease_token=? AND event_id=? AND outcome='LEASED'",now,row.token(),row.id());
            if(audit!=1) return dead(row,"AUDIT_MISSING");
        }
        if(row.attempts()<0 || row.total()<row.attempts() || row.total()==Long.MAX_VALUE) return dead(row,"INVALID_STATE");
        if(row.attempts()>=MAX_ATTEMPTS) return dead(row,"EXHAUSTED");
        String token=UUID.randomUUID().toString();
        Timestamp until=Timestamp.from(now.toInstant().plusSeconds(LEASE_SECONDS));
        int attempt=Math.addExact(row.attempts(),1);
        db.update("UPDATE content_event_delivery SET state='LEASED',attempts=?,total_attempts=?,due_at=?,lease_token=?,last_error=NULL WHERE event_id=?",
                attempt,Math.addExact(row.total(),1),until,token,row.id());
        db.update("INSERT INTO content_event_attempt_audit(lease_token,event_id,attempt_number,claimed_at,outcome) VALUES(?,?,?,?, 'LEASED')",
                token,row.id(),attempt,now);
        return new Claim(row.id(),row.family(),row.type(),token,attempt,false);
    }
    private Claim dead(Row row,String category) {
        db.update("UPDATE content_event_delivery SET state='DEAD',due_at=NULL,lease_token=NULL,last_error=? WHERE event_id=?",category,row.id());
        return new Claim(row.id(),row.family(),row.type(),null,row.attempts(),true);
    }
    private boolean lockDelivery(String eventId) {
        // The following conditional UPDATE must start after this lock is acquired, not before a possible wait.
        return !db.query("SELECT event_id FROM content_event_delivery WHERE event_id=? FOR UPDATE",
                (r,n) -> r.getString(1),eventId).isEmpty();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public boolean sent(Claim claim,String streamId) {
        if(claim==null || claim.token()==null || streamId==null || !streamId.matches("[0-9]{1,20}-[0-9]{1,20}"))
            throw new IllegalArgumentException("Invalid delivery result");
        if(!lockDelivery(claim.eventId())) return false;
        int changed=db.update("UPDATE content_event_delivery SET state='SENT',due_at=NULL,lease_token=NULL,delivered_at=CURRENT_TIMESTAMP(6),stream_id=?,last_error=NULL "+
                "WHERE event_id=? AND state='LEASED' AND lease_token=? AND due_at>CURRENT_TIMESTAMP(6)",
                streamId,claim.eventId(),claim.token());
        if(changed==1 && db.update("UPDATE content_event_attempt_audit SET outcome='SENT',finished_at=CURRENT_TIMESTAMP(6),stream_id=? "+
                "WHERE lease_token=? AND event_id=? AND outcome='LEASED'",streamId,claim.token(),claim.eventId())!=1)
            throw new IllegalStateException("Missing attempt audit");
        return changed==1;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public boolean failed(Claim claim,String category,boolean permanent) {
        if(claim==null || claim.token()==null || !List.of("TRANSPORT","DATABASE","INVALID_EVENT","MISSING_FACT").contains(category))
            throw new IllegalArgumentException("Invalid failure category");
        if(!lockDelivery(claim.eventId())) return false;
        Timestamp now=db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
        boolean dead=permanent || claim.attempt()>=MAX_ATTEMPTS;
        long seconds=Math.min(60,1L << Math.min(6,Math.max(0,claim.attempt()-1)));
        Timestamp due=dead?null:Timestamp.from(now.toInstant().plus(Duration.ofSeconds(seconds)));
        int changed=db.update("UPDATE content_event_delivery SET state=?,due_at=?,lease_token=NULL,last_error=? "+
                "WHERE event_id=? AND state='LEASED' AND lease_token=? AND due_at>CURRENT_TIMESTAMP(6)",
                dead?"DEAD":"PENDING",due,category,claim.eventId(),claim.token());
        if(changed==1 && db.update("UPDATE content_event_attempt_audit SET outcome=?,finished_at=?,error_category=? "+
                "WHERE lease_token=? AND event_id=? AND outcome='LEASED'",
                dead?"DEAD":"RETRY",now,category,claim.token(),claim.eventId())!=1)
            throw new IllegalStateException("Missing attempt audit");
        return changed==1;
    }
}
