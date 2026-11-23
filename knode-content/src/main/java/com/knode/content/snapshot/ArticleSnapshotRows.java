package com.knode.content.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.RawValue;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.knode.content.snapshot.SnapshotFailure.Code.RESPONSE_TOO_LARGE;
import static com.knode.content.snapshot.SnapshotSettings.*;

@Repository
public class ArticleSnapshotRows {
    enum Visibility { PUBLIC, DRAFT, WITHDRAWN, DELETED, MISSING }
    enum Decision { READABLE, DENIED }

    record Batch<T>(List<T> items) {}
    record VisibilityItem(String articleId, String sourceRevision, Visibility visibility, Decision decision) {}
    record SnapshotItem(String articleId, String sourceRevision, Visibility visibility, Decision decision,
                        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode snapshot) {}
    record Taxonomy(String id, String name) {}
    record Projection(String articleId, String sourceRevision, String publicationFactId, String authorId, String title,
                      String summary, String content, String thumbnail, String isTop, String isComment, Taxonomy category,
                      List<Taxonomy> tags, String createTime, String publishedAt, String viewCount, String counterRevision) {}
    record SizedId(long id, long bytes) {}
    record Stored(long id, String json, int bytes) {}
    private record Location(long revision, Visibility visibility, long bytes) {
        static final Location MISSING = new Location(0, Visibility.MISSING, 0);
        String revisionText() {
            return visibility == Visibility.MISSING ? null : Long.toString(revision);
        }
        Decision decision() {
            return visibility == Visibility.PUBLIC ? Decision.READABLE : Decision.DENIED;
        }
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ArticleSnapshotRows(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Batch<VisibilityItem> visibility(List<Long> ids) {
        Map<Long, Location> found = locate(ids, false);
        return new Batch<>(ids.stream().map(id -> {
            Location row = found.getOrDefault(id, Location.MISSING);
            return new VisibilityItem(id.toString(), row.revisionText(), row.visibility(), row.decision());
        }).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Batch<SnapshotItem> current(List<Long> ids) {
        Map<Long, Location> found = locate(ids, true);
        List<Long> readable = new ArrayList<>();
        long rawBytes = 0;
        for (long id : ids) {
            Location row = found.getOrDefault(id, Location.MISSING);
            if (row.visibility() == Visibility.PUBLIC) {
                if (row.bytes() < 0 || row.bytes() > MAX_ITEM_BYTES - rawBytes) throw new SnapshotFailure(RESPONSE_TOO_LARGE);
                rawBytes += row.bytes();
                readable.add(id);
            }
        }
        Map<Long, JsonNode> bodies = new HashMap<>();
        int bytes = 0;
        for (Stored item : publicProjections(readable)) {
            if (item.bytes() > MAX_ITEM_BYTES - bytes) throw new SnapshotFailure(RESPONSE_TOO_LARGE);
            bytes += item.bytes();
            bodies.put(item.id(), asJson(item.json()));
        }
        return new Batch<>(ids.stream().map(id -> {
            Location row = found.getOrDefault(id, Location.MISSING);
            return new SnapshotItem(id.toString(), row.revisionText(), row.visibility(), row.decision(), bodies.get(id));
        }).toList());
    }

    private Map<Long, Location> locate(List<Long> ids, boolean measureBody) {
        String size = measureBody ? ", CASE WHEN a.status='0' AND a.deleted=0 THEN OCTET_LENGTH(a.content) ELSE 0 END" : "";
        Map<Long, Location> result = new HashMap<>();
        db.query("""
                SELECT a.id,a.source_revision,a.status,a.deleted,
                    CASE WHEN a.status='1' AND a.deleted=0 THEN
                        a.publication_fact_id IS NOT NULL OR a.published_at IS NOT NULL
                        OR EXISTS (SELECT 1 FROM content_article_event e WHERE e.article_id=a.id)
                    ELSE FALSE END AS public_history
                """ + size + " FROM content_article a WHERE a.id IN (" + placeholders(ids.size()) + ")", rs -> {
            long revision = rs.getLong("source_revision");
            if (revision <= 0) throw new IllegalStateException("Invalid stored article revision");
            Visibility state = classify(rs);
            result.put(rs.getLong("id"), new Location(revision, state, measureBody ? rs.getLong(6) : 0));
        }, ids.toArray());
        return result;
    }

    private static Visibility classify(ResultSet row) throws SQLException {
        if (row.getBoolean("deleted")) return Visibility.DELETED;
        return switch (row.getString("status")) {
            case "0" -> Visibility.PUBLIC;
            case "1" -> row.getBoolean("public_history") ? Visibility.WITHDRAWN : Visibility.DRAFT;
            default -> throw new IllegalStateException("Invalid stored article state");
        };
    }

    // Called inside the export's single nonlocking REPEATABLE READ view.
    List<SizedId> nextPublicIds(long after) {
        return db.query("""
                SELECT id,OCTET_LENGTH(content) FROM content_article
                WHERE status='0' AND deleted=0 AND id>? ORDER BY id LIMIT ?
                """, (rs, n) -> new SizedId(rs.getLong(1), rs.getLong(2)), after, EXPORT_WORK_SIZE);
    }

    List<Stored> publicProjections(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String arguments = placeholders(ids.size());
        Map<Long, List<Taxonomy>> tags = new HashMap<>();
        db.query("""
                SELECT x.article_id,t.id,t.name FROM content_article_tag x JOIN content_tag t ON t.id=x.tag_id
                WHERE t.del_flag=0 AND x.article_id IN (
                """ + arguments + ") ORDER BY x.article_id,t.id LIMIT " + (ids.size() * 50 + 1), rs -> {
            long articleId = rs.getLong(1);
            List<Taxonomy> list = tags.computeIfAbsent(articleId, id -> new ArrayList<>());
            if (list.size() == 50) throw new IllegalStateException("Invalid stored article tag cardinality");
            list.add(new Taxonomy(positive(rs.getLong(2)), rs.getString(3)));
        }, ids.toArray());
        Map<Long, Stored> result = new HashMap<>();
        db.query("""
                SELECT a.id,a.source_revision,a.publication_fact_id,a.author_id,a.title,a.summary,a.content,a.thumbnail,
                    a.is_top,a.is_comment,a.category_id,c.name,a.created_at,a.published_at,a.view_count,a.counter_revision
                FROM content_article a JOIN content_category c ON c.id=a.category_id
                WHERE a.status='0' AND a.deleted=0 AND a.id IN (
                """ + arguments + ")", rs -> {
            long id = rs.getLong(1);
            String content = rs.getString(7);
            if (content.length() > 1_000_000) throw new SnapshotFailure(RESPONSE_TOO_LARGE);
            Projection projection = new Projection(positive(id), positive(rs.getLong(2)), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), content, rs.getString(8), rs.getString(9), rs.getString(10),
                    new Taxonomy(positive(rs.getLong(11)), rs.getString(12)), tags.getOrDefault(id, List.of()),
                    rs.getTimestamp(13).toInstant().toString(), instant(rs.getTimestamp(14)),
                    nonnegative(rs.getLong(15)), nonnegative(rs.getLong(16)));
            String encoded = encode(projection);
            int size = encoded.getBytes(StandardCharsets.UTF_8).length;
            if (size > MAX_ITEM_BYTES) throw new SnapshotFailure(RESPONSE_TOO_LARGE);
            result.put(id, new Stored(id, encoded, size));
        }, ids.toArray());
        if (result.size() != ids.size()) throw new IllegalStateException("Public article projection unavailable");
        return ids.stream().map(result::get).toList();
    }

    JsonNode asJson(String raw) {
        return json.getNodeFactory().rawValueNode(new RawValue(raw));
    }

    private String encode(Projection projection) {
        try {
            return json.writeValueAsString(projection);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode article snapshot", e);
        }
    }

    private static String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static String positive(long value) {
        if (value <= 0) throw new IllegalStateException("Invalid article ID or version");
        return Long.toString(value);
    }

    private static String nonnegative(long value) {
        if (value < 0) throw new IllegalStateException("Invalid article counter");
        return Long.toString(value);
    }

    static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
