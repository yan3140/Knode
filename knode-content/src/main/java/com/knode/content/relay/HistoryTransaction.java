package com.knode.content.relay;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoryTransaction {
    private final JdbcTemplate db;
    public HistoryTransaction(JdbcTemplate db) { this.db=db; }
    private record Cursor(String id,long passes) {}
    private record Fact(String id,String type) {}
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public int scan(String family,int limit) {
        if(limit<1 || limit>20) throw new IllegalArgumentException("Invalid batch");
        String table=switch(family) {
            case "ARTICLE" -> "content_article_event";
            case "COMMENT" -> "content_comment_event";
            case "COUNTER" -> "content_article_counter_event";
            default -> throw new IllegalArgumentException("Invalid family");
        };
        Cursor cursor=db.queryForObject("SELECT cursor_event_id,completed_passes FROM content_event_backfill WHERE family=? FOR UPDATE",
                (r,n) -> new Cursor(r.getString(1),r.getLong(2)),family);
        String type="COUNTER".equals(family)?"'ArticleCounterChanged'":"event_type";
        List<Fact> rows=db.query("SELECT event_id,"+type+" FROM "+table+" WHERE event_id>? ORDER BY event_id LIMIT ?",
                (r,n) -> new Fact(r.getString(1),r.getString(2)),cursor.id(),limit);
        Timestamp now=db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
        for(Fact fact:rows) {
            boolean local="COMMENT".equals(family) && fact.type().startsWith("LinkComment");
            db.update("INSERT INTO content_event_delivery(event_id,family,event_type,state,attempts,total_attempts,due_at) VALUES(?,?,?,?,0,0,?) "+
                    "ON DUPLICATE KEY UPDATE event_id=event_id",fact.id(),family,fact.type(),local?"LOCAL_ONLY":"PENDING",local?null:now);
            Integer matches=db.queryForObject("SELECT COUNT(*) FROM content_event_delivery WHERE event_id=? AND family=? AND event_type=?",
                    Integer.class,fact.id(),family,fact.type());
            if(matches==null || matches!=1) throw new IllegalStateException("Conflicting fact identity");
        }
        boolean done=rows.size()<limit;
        String next=done?"":rows.get(rows.size()-1).id();
        long passes=done?Math.addExact(cursor.passes(),1):cursor.passes();
        db.update("UPDATE content_event_backfill SET cursor_event_id=?,completed_passes=?,last_error=NULL WHERE family=?",next,passes,family);
        return rows.size();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void recordFailure(String family) {
        if(!List.of("ARTICLE","COMMENT","COUNTER").contains(family)) throw new IllegalArgumentException("Invalid family");
        db.update("UPDATE content_event_backfill SET failure_count=failure_count+1,last_error='SCAN_FAILED',last_failed_at=CURRENT_TIMESTAMP(6) WHERE family=?",family);
    }
}
