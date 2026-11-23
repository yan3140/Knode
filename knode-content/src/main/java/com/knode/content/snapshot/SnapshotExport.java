package com.knode.content.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.knode.content.snapshot.ArticleSnapshotRows.placeholders;
import static com.knode.content.snapshot.SnapshotCursor.Kind.*;
import static com.knode.content.snapshot.SnapshotFailure.Code.*;
import static com.knode.content.snapshot.SnapshotSettings.*;

@Service
public class SnapshotExport {
    record Handle(String snapshotId, String incrementalCursor, Instant expiresAt) {}
    record Page(String snapshotId, List<JsonNode> items, String nextCursor, boolean done, String incrementalCursor) {}

    private record Header(String id, String requestId, String owner, SnapshotRequests.Purpose purpose, String epoch,
                          long cut, Instant expires, Instant feedExpires, boolean itemsPruned) {}

    private static final String HEADER = """
            SELECT snapshot_id,request_id,owner_service,purpose,feed_epoch,cut_sequence,expires_at,feed_expires_at,items_pruned
            FROM content_article_snapshot WHERE
            """;
    private final JdbcTemplate db;
    private final ArticleSnapshotRows rows;
    private final ArticleChangeJournal journal;
    private final SnapshotCursor cursors;
    private final SnapshotSettings settings;

    public SnapshotExport(JdbcTemplate db, ArticleSnapshotRows rows, ArticleChangeJournal journal,
                          SnapshotCursor cursors, SnapshotSettings settings) {
        this.db = db;
        this.rows = rows;
        this.journal = journal;
        this.cursors = cursors;
        this.settings = settings;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Handle create(String owner, SnapshotRequests.Create request) {
        lockAdmission();
        String key = requestKey(owner, request.requestId());
        // The gate's locking read does not establish an InnoDB read view. This plain read does.
        Header existing = find("request_key", key);
        Instant now = dbNow();
        if (existing != null) {
            if (!existing.owner().equals(owner) || !existing.requestId().equals(request.requestId())
                    || existing.purpose() != request.purpose()) throw new SnapshotFailure(IDEMPOTENCY_CONFLICT);
            requireActive(existing, now);
            return handle(existing);
        }
        if (db.queryForObject("SELECT COUNT(*) FROM content_article_snapshot WHERE items_pruned=0", Long.class) >= MAX_STORED_EXPORTS
                || db.queryForObject("SELECT COUNT(*) FROM content_article_snapshot", Long.class) >= MAX_REQUEST_RECORDS) {
            throw new SnapshotFailure(RATE_LIMITED);
        }

        String id = UUID.randomUUID().toString();
        long cut = journal.currentHead();
        db.update("""
                INSERT INTO content_article_snapshot
                (snapshot_id,request_key,request_id,owner_service,purpose,feed_epoch,cut_sequence,expires_at,feed_expires_at,items_pruned)
                VALUES(?,?,?,?,?,?,?,?,?,0)
                """, id, key, request.requestId(), owner, request.purpose().name(), settings.epoch(), cut,
                Timestamp.from(now.plus(SNAPSHOT_TTL)), Timestamp.from(now.plus(FEED_TTL)));
        // Save only bounded chunks. Plain reads share the head's read view; no business writer lock is held.
        capture(id);
        Instant completed = dbNow();
        Instant expires = completed.plus(SNAPSHOT_TTL);
        Instant feedExpires = completed.plus(FEED_TTL);
        db.update("UPDATE content_article_snapshot SET expires_at=?,feed_expires_at=? WHERE snapshot_id=?",
                Timestamp.from(expires), Timestamp.from(feedExpires), id);
        return handle(new Header(id, request.requestId(), owner, request.purpose(), settings.epoch(), cut, expires, feedExpires, false));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Page page(String id, String owner, SnapshotRequests.Page request) {
        Header header = find("snapshot_id", id);
        if (header == null) throw new SnapshotFailure(SNAPSHOT_EXPIRED);
        if (!header.owner().equals(owner)) throw new SnapshotFailure(SERVICE_PERMISSION_DENIED);
        Instant now = dbNow();
        requireActive(header, now);
        long after = 0;
        if (request.cursor() != null) {
            SnapshotCursor.Position cursor = cursors.open(request.cursor(), PAGE, owner, now);
            if (!id.equals(cursor.snapshotId()) || header.purpose() != cursor.purpose()) throw new SnapshotFailure(INVALID_ARGUMENT);
            after = cursor.offset();
        }

        List<ArticleSnapshotRows.SizedId> available = sizes(id, after, request.limit() + 1);
        List<Long> selected = withinBudget(available, request.limit(), IllegalStateException::new);
        List<JsonNode> items = readPage(id, selected);
        boolean done = selected.size() == available.size();
        String next = done ? null : cursors.issue(PAGE, owner, header.purpose(), id,
                selected.get(selected.size() - 1), header.expires());
        return new Page(id, items, next, done, handle(header).incrementalCursor());
    }

    private void capture(String id) {
        long after = 0;
        int articles = 0;
        long storedBytes = 0;
        while (true) {
            List<ArticleSnapshotRows.SizedId> available = rows.nextPublicIds(after);
            if (available.isEmpty()) return;
            List<Long> ids = withinBudget(available, EXPORT_WORK_SIZE, () -> new SnapshotFailure(SNAPSHOT_TOO_LARGE));
            if (ids.size() > MAX_EXPORT_ITEMS - articles) throw new SnapshotFailure(SNAPSHOT_TOO_LARGE);
            List<ArticleSnapshotRows.Stored> projections;
            try {
                projections = rows.publicProjections(ids);
            } catch (SnapshotFailure failure) {
                if (failure.status() == 413) throw new SnapshotFailure(SNAPSHOT_TOO_LARGE);
                throw failure;
            }
            for (ArticleSnapshotRows.Stored item : projections) {
                storedBytes += item.bytes();
                if (storedBytes > MAX_EXPORT_BYTES) throw new SnapshotFailure(SNAPSHOT_TOO_LARGE);
            }
            db.batchUpdate("""
                    INSERT INTO content_article_snapshot_item(snapshot_id,article_id,payload_bytes,projection_json)
                    VALUES(?,?,?,?)
                    """, projections, projections.size(), (ps, item) -> {
                ps.setString(1, id);
                ps.setLong(2, item.id());
                ps.setInt(3, item.bytes());
                ps.setString(4, item.json());
            });
            articles += ids.size();
            after = ids.get(ids.size() - 1);
        }
    }

    private List<JsonNode> readPage(String id, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(ids);
        List<JsonNode> result = db.query("""
                SELECT CASE WHEN OCTET_LENGTH(projection_json)=payload_bytes THEN projection_json ELSE NULL END
                FROM content_article_snapshot_item WHERE snapshot_id=? AND article_id IN (
                """ + placeholders(ids.size()) + ") ORDER BY article_id", (rs, n) -> {
            String raw = rs.getString(1);
            if (raw == null) throw new IllegalStateException("Stored snapshot item unavailable");
            return rows.asJson(raw);
        }, args.toArray());
        if (result.size() != ids.size()) throw new IllegalStateException("Stored snapshot page is incomplete");
        return result;
    }

    private List<ArticleSnapshotRows.SizedId> sizes(String id, long after, int limit) {
        return db.query("""
                SELECT article_id,payload_bytes FROM content_article_snapshot_item
                WHERE snapshot_id=? AND article_id>? ORDER BY article_id LIMIT ?
                """, (rs, n) -> new ArticleSnapshotRows.SizedId(rs.getLong(1), rs.getLong(2)), id, after, limit);
    }

    private static List<Long> withinBudget(List<ArticleSnapshotRows.SizedId> available, int limit,
                                           java.util.function.Supplier<? extends RuntimeException> tooLarge) {
        List<Long> ids = new ArrayList<>();
        long remaining = MAX_ITEM_BYTES;
        for (ArticleSnapshotRows.SizedId item : available) {
            if (ids.size() == limit) break;
            if (item.id() <= 0 || item.bytes() < 0 || item.bytes() > MAX_ITEM_BYTES) {
                if (!ids.isEmpty()) break;
                throw tooLarge.get();
            }
            if (item.bytes() > remaining) break;
            ids.add(item.id());
            remaining -= item.bytes();
        }
        return ids;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void cleanExpired() {
        try {
            lockAdmission();
        } catch (SnapshotFailure failure) {
            if (failure.status() == 429) return;
            throw failure;
        }
        Instant now = dbNow();
        List<String> expired = db.queryForList("""
                SELECT snapshot_id FROM content_article_snapshot
                WHERE items_pruned=0 AND expires_at<=? ORDER BY expires_at,snapshot_id LIMIT 1
                """, String.class, Timestamp.from(now));
        if (!expired.isEmpty()) {
            String id = expired.get(0);
            List<Long> ids = withinBudget(sizes(id, 0, MAX_ITEMS), MAX_ITEMS, IllegalStateException::new);
            if (ids.isEmpty()) {
                db.update("UPDATE content_article_snapshot SET items_pruned=1 WHERE snapshot_id=?", id);
            } else {
                List<Object> args = new ArrayList<>();
                args.add(id);
                args.addAll(ids);
                db.update("DELETE FROM content_article_snapshot_item WHERE snapshot_id=? AND article_id IN ("
                        + placeholders(ids.size()) + ")", args.toArray());
            }
        }
        List<String> forgotten = db.queryForList("""
                SELECT snapshot_id FROM content_article_snapshot
                WHERE items_pruned=1 AND expires_at<=? ORDER BY expires_at,snapshot_id LIMIT 10
                """, String.class, Timestamp.from(now.minus(REQUEST_TOMBSTONE_TTL)));
        if (!forgotten.isEmpty()) {
            db.update("DELETE FROM content_article_snapshot WHERE snapshot_id IN (" + placeholders(forgotten.size()) + ")",
                    forgotten.toArray());
        }
    }

    private Header find(String column, String value) {
        List<Header> result = db.query(HEADER + column + "=?", (rs, n) -> header(rs), value);
        return result.isEmpty() ? null : result.get(0);
    }

    private static Header header(ResultSet rs) throws SQLException {
        return new Header(rs.getString(1), rs.getString(2), rs.getString(3),
                SnapshotRequests.Purpose.valueOf(rs.getString(4)), rs.getString(5), rs.getLong(6),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(), rs.getBoolean(9));
    }

    private void requireActive(Header header, Instant now) {
        if (header.itemsPruned() || !settings.epoch().equals(header.epoch()) || !now.isBefore(header.expires())) {
            throw new SnapshotFailure(SNAPSHOT_EXPIRED);
        }
    }

    private Handle handle(Header header) {
        return new Handle(header.id(), cursors.issue(CHANGES, header.owner(), header.purpose(), null, header.cut(), header.feedExpires()),
                header.expires());
    }

    private Instant dbNow() {
        // Use millisecond precision consistently for the response, persisted expiry, and signed cursor.
        return Instant.ofEpochMilli(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant().toEpochMilli());
    }

    private void lockAdmission() {
        try {
            db.queryForObject("SELECT id FROM content_article_snapshot_gate WHERE id=1 FOR UPDATE NOWAIT", Long.class);
        } catch (DataAccessException e) {
            if (e instanceof PessimisticLockingFailureException) throw new SnapshotFailure(RATE_LIMITED);
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && (sql.getErrorCode() == 3572 || sql.getErrorCode() == 50200)) {
                    throw new SnapshotFailure(RATE_LIMITED);
                }
            }
            throw e;
        }
    }

    private static String requestKey(String owner, String requestId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((owner + "\n" + requestId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Snapshot request identity unavailable", e);
        }
    }
}
