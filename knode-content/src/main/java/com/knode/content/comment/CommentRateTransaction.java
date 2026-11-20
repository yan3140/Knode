package com.knode.content.comment;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CommentRateTransaction {
    private final JdbcTemplate db;
    public CommentRateTransaction(JdbcTemplate db) { this.db=db; }
    public record Key(String kind,String hash) implements Comparable<Key> {
        public int compareTo(Key other) { int c=kind.compareTo(other.kind); return c!=0?c:hash.compareTo(other.hash); }
    }
    private record Rule(Key key,int maximum,int seconds) {}
    private record Window(int hits,Instant expires) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=5)
    public void cleanup() {
        List<Key> expired=db.query("SELECT kind,key_hash FROM content_comment_limit WHERE expires_at<=CURRENT_TIMESTAMP(6) ORDER BY expires_at,kind,key_hash LIMIT 100",
            (r,n) -> new Key(r.getString(1),r.getString(2)));
        Collections.sort(expired);
        for(Key key:expired) db.update("DELETE FROM content_comment_limit WHERE kind=? AND key_hash=? AND expires_at<=CURRENT_TIMESTAMP(6)",key.kind(),key.hash());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,timeout=5)
    public int consume(String userHash,String ipHash,String duplicateHash) {
        List<Rule> rules=new ArrayList<>(List.of(new Rule(new Key("D",duplicateHash),1,30),
                new Rule(new Key("I",ipHash),30,60),new Rule(new Key("U",userHash),10,60)));
        rules.sort(Comparator.comparing(Rule::key));
        Map<Key,Window> windows=new HashMap<>();
        for(Rule rule:rules) {
            Key key=rule.key();
            db.update("INSERT INTO content_comment_limit(kind,key_hash,hits,expires_at) VALUES(?,?,0,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE hits=hits",key.kind(),key.hash());
            List<Window> rows=db.query("SELECT hits,expires_at FROM content_comment_limit WHERE kind=? AND key_hash=? FOR UPDATE",
                    (r,n) -> new Window(r.getInt(1),r.getTimestamp(2).toInstant()),key.kind(),key.hash());
            if(rows.size()!=1 || rows.get(0).hits()<0) throw new IllegalStateException("Rate state unavailable");
            windows.put(key,rows.get(0));
        }
        Instant now=Objects.requireNonNull(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class)).toInstant();
        int retry=0;
        for(Rule rule:rules) {
            Window window=windows.get(rule.key());
            if (!window.expires().isAfter(now)) windows.put(rule.key(),new Window(0,now.plusSeconds(rule.seconds())));
            else if (window.hits()>=rule.maximum()) {
                long millis=Duration.between(now,window.expires()).toMillis();
                retry=Math.max(retry,(int)Math.max(1,Math.min(3600,(millis+999)/1000)));
            }
        }
        if(retry>0) return retry;
        for(Rule rule:rules) {
            Window window=windows.get(rule.key()); Key key=rule.key();
            db.update("UPDATE content_comment_limit SET hits=?,expires_at=? WHERE kind=? AND key_hash=?",
                    window.hits()+1,Timestamp.from(window.expires()),key.kind(),key.hash());
        }
        return 0;
    }
}
