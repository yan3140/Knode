package com.knode.content.relay;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FactReader {
    private final JdbcTemplate db;
    private final EventGuard guard;
    public FactReader(JdbcTemplate db,EventGuard guard) { this.db=db; this.guard=guard; }
    public String load(RelayTransaction.Claim claim) {
        String table=switch(claim.family()) {
            case "ARTICLE" -> "content_article_event";
            case "COMMENT" -> "content_comment_event";
            case "COUNTER" -> "content_article_counter_event";
            default -> throw new EventGuard.InvalidEvent();
        };
        List<Long> sizes=db.query("SELECT OCTET_LENGTH(payload) FROM "+table+" WHERE event_id=?",(r,n) -> r.getLong(1),claim.eventId());
        if(sizes.size()!=1) throw new MissingFact();
        if(sizes.get(0)<0 || sizes.get(0)>EventGuard.MAX_BYTES) throw new EventGuard.InvalidEvent();
        List<String> rows=db.query("SELECT payload FROM "+table+" WHERE event_id=?",(r,n) -> r.getString(1),claim.eventId());
        if(rows.size()!=1) throw new MissingFact();
        String raw=rows.get(0);guard.validate(claim,raw);return raw;
    }
    public static final class MissingFact extends RuntimeException {}
}
