package com.knode.content.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.knode.content.relay.EventGuard;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.knode.content.snapshot.SnapshotCursor.Kind.CHANGES;
import static com.knode.content.snapshot.SnapshotFailure.Code.CURSOR_EXPIRED;
import static com.knode.content.snapshot.SnapshotSettings.*;

@Service
public class ArticleChangeFeed {
    record Commit(String commitSequence, List<JsonNode> events) {}
    record Page(List<Commit> commits, String nextCursor, boolean done, Instant cursorExpiresAt) {}
    private record Batch(long sequence, int count, int bytes) {}
    private record Fact(long sequence, int position, String id, String family, String type, int bytes, String raw) {}

    private final JdbcTemplate db;
    private final ArticleChangeJournal journal;
    private final SnapshotCursor cursors;
    private final EventGuard guard;
    private final ArticleSnapshotRows snapshots;

    public ArticleChangeFeed(JdbcTemplate db, ArticleChangeJournal journal, SnapshotCursor cursors,
                             EventGuard guard, ArticleSnapshotRows snapshots) {
        this.db = db;
        this.journal = journal;
        this.cursors = cursors;
        this.guard = guard;
        this.snapshots = snapshots;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Page read(String owner, SnapshotRequests.Page request) {
        long head = journal.currentHead();
        Instant now = Instant.ofEpochMilli(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant().toEpochMilli());
        SnapshotCursor.Position cursor = cursors.open(request.cursor(), CHANGES, owner, now);
        long after = cursor.offset();
        if (after > head) throw new SnapshotFailure(CURSOR_EXPIRED);
        List<Batch> selected = after == head ? List.of() : selectBatches(after, head, request.limit());
        long through = selected.isEmpty() ? after : selected.get(selected.size() - 1).sequence();
        List<Commit> commits = readCommits(after, through, selected);
        Instant expires = now.plus(FEED_TTL);
        String next = cursors.issue(CHANGES, owner, cursor.purpose(), null, through, expires);
        return new Page(commits, next, through == head, expires);
    }

    private List<Batch> selectBatches(long after, long head, int limit) {
        List<Batch> available = db.query("""
                SELECT commit_sequence,fact_count,payload_bytes FROM content_article_change_batch
                WHERE commit_sequence>? AND commit_sequence<=? ORDER BY commit_sequence LIMIT ?
                """, (rs, n) -> new Batch(rs.getLong(1), rs.getInt(2), rs.getInt(3)), after, head, limit);
        if (available.isEmpty()) throw inconsistent();
        List<Batch> selected = new ArrayList<>();
        long expected = after;
        int facts = 0;
        int bytes = 0;
        for (Batch batch : available) {
            if (batch.sequence() != Math.addExact(expected, 1)
                    || batch.count() < 1 || batch.count() > ArticleChangeJournal.MAX_FACTS_PER_COMMIT
                    || batch.bytes() < 1 || batch.bytes() > MAX_ITEM_BYTES) throw inconsistent();
            if (batch.count() > MAX_ITEMS - facts || batch.bytes() > MAX_ITEM_BYTES - bytes) break;
            selected.add(batch);
            expected = batch.sequence();
            facts += batch.count();
            bytes += batch.bytes();
        }
        return selected;
    }

    private List<Commit> readCommits(long after, long through, List<Batch> selected) {
        if (selected.isEmpty()) return List.of();
        List<Fact> refs = db.query("""
                SELECT f.commit_sequence,f.fact_position,f.event_id,f.family,f.event_type,f.payload_bytes,
                    CASE
                      WHEN f.family='ARTICLE' AND OCTET_LENGTH(a.payload)=f.payload_bytes
                           AND f.payload_bytes BETWEEN 1 AND 8000000 THEN a.payload
                      WHEN f.family='COUNTER' AND OCTET_LENGTH(c.payload)=f.payload_bytes
                           AND f.payload_bytes BETWEEN 1 AND 8000000 THEN c.payload
                      ELSE NULL
                    END
                FROM content_article_change_fact f
                LEFT JOIN content_article_event a ON f.family='ARTICLE' AND a.event_id=f.event_id
                LEFT JOIN content_article_counter_event c ON f.family='COUNTER' AND c.event_id=f.event_id
                WHERE f.commit_sequence>? AND f.commit_sequence<=?
                ORDER BY f.commit_sequence,f.fact_position LIMIT ?
                """, (rs, n) -> new Fact(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getInt(6),rs.getString(7)), after, through, MAX_ITEMS + 1);

        List<Commit> out = new ArrayList<>();
        int index = 0;
        for (Batch batch : selected) {
            List<JsonNode> events = new ArrayList<>();
            int remaining = batch.bytes();
            for (int position = 1; position <= batch.count(); position++) {
                if (index >= refs.size()) throw inconsistent();
                Fact fact = refs.get(index++);
                if (fact.sequence() != batch.sequence() || fact.position() != position || fact.raw() == null
                        || fact.bytes() <= 0 || fact.bytes() > remaining
                        || fact.raw().getBytes(StandardCharsets.UTF_8).length != fact.bytes()) throw inconsistent();
                if (!"ARTICLE".equals(fact.family()) && !"COUNTER".equals(fact.family())) throw inconsistent();
                guard.validate(fact.id(), fact.family(), fact.type(), fact.raw());
                events.add(snapshots.asJson(fact.raw()));
                remaining -= fact.bytes();
            }
            if (remaining != 0) throw inconsistent();
            out.add(new Commit(Long.toString(batch.sequence()), List.copyOf(events)));
        }
        if (index != refs.size()) throw inconsistent();
        return List.copyOf(out);
    }

    private static IllegalStateException inconsistent() {
        return new IllegalStateException("Article change journal is incomplete");
    }
}
