package com.knode.community.follow;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FollowTransaction {
    public record FollowRow(long userId, Instant followTime) {}
    public record FollowEdge(long followerId, long followeeId) {}
    public record RelationSnapshot(List<FollowEdge> outgoing, List<FollowEdge> incoming) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RelationBatchItem(
            String userId,
            String followingCount,
            String followerCount,
            Boolean viewerFollowing) {}

    private static final int DEFAULT_MAX_RETRY = 8;

    private final JdbcTemplate db;
    private final int scanLimit;

    public FollowTransaction(JdbcTemplate db,
                             @Value("${KNODE_COMMUNITY_RELATION_SCAN_LIMIT:20000}") int scanLimit) {
        if (scanLimit < 1 || scanLimit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid community relation scan limit");
        }
        this.db = db;
        this.scanLimit = scanLimit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public boolean follow(long followerId, long followeeId) {
        if (existsRelation(followerId, followeeId)) {
            return false;
        }
        boolean created;
        try {
            int inserted = db.update(
                    "INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, CURRENT_TIMESTAMP(3))",
                    followerId,
                    followeeId);
            created = inserted > 0;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
        if (created) {
            enqueueFollowNotificationIfFirstTime(followerId, followeeId);
        }
        return created;
    }

    private void enqueueFollowNotificationIfFirstTime(long followerId, long followeeId) {
        String taskKey = "FOLLOWED:" + followerId + ":" + followeeId;
        if (existsDeliveryTask(taskKey)) {
            return;
        }
        String syntheticEventId = UUID.nameUUIDFromBytes(taskKey.getBytes(StandardCharsets.UTF_8)).toString();
        try {
            db.update("""
                    INSERT INTO yan_notification_delivery_outbox(
                        event_id, task_key, receiver_id, actor_id, article_id, comment_id, root_comment_id,
                        type, content, status, retry_count, max_retry, next_retry_at,
                        rerun_requested, process_count, create_time, update_time
                    ) VALUES (?, ?, ?, ?, NULL, NULL, NULL, 'FOLLOWED', NULL, 'PENDING', 0, ?, CURRENT_TIMESTAMP(3), 0, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                    """,
                    syntheticEventId,
                    taskKey,
                    followeeId,
                    followerId,
                    DEFAULT_MAX_RETRY);
        } catch (DuplicateKeyException duplicate) {
            if (!existsDeliveryTask(taskKey)) {
                throw duplicate;
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void unfollow(long followerId, long followeeId) {
        db.update(
                "DELETE FROM yan_user_follow WHERE follower_id = ? AND followee_id = ?",
                followerId,
                followeeId);
    }

    @Transactional(readOnly = true, timeout = 5)
    public boolean isFollowing(long followerId, long followeeId) {
        if (followerId == followeeId) {
            return false;
        }
        return existsRelation(followerId, followeeId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 1)
    public List<FollowRow> scanFollowList(long userId, boolean following) {
        String query = following
                ? "SELECT followee_id, create_time FROM yan_user_follow WHERE follower_id = ? ORDER BY id DESC LIMIT ?"
                : "SELECT follower_id, create_time FROM yan_user_follow WHERE followee_id = ? ORDER BY id DESC LIMIT ?";
        List<FollowRow> rows = db.query(query,
                (rs, n) -> new FollowRow(rs.getLong(1), toInstant(rs.getTimestamp(2))),
                userId, scanLimit + 1);
        requireCompleteScan(rows.size());
        return rows;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 1)
    public RelationSnapshot scanRelations(List<Long> distinctUserIds) {
        if (distinctUserIds.isEmpty()) {
            return new RelationSnapshot(List.of(), List.of());
        }
        List<FollowEdge> outgoing = scanEdges(distinctUserIds, "follower_id", scanLimit + 1);
        requireCompleteScan(outgoing.size());
        List<FollowEdge> incoming = scanEdges(distinctUserIds, "followee_id", scanLimit - outgoing.size() + 1);
        requireCompleteScan(outgoing.size() + incoming.size());
        return new RelationSnapshot(outgoing, incoming);
    }

    private List<FollowEdge> scanEdges(List<Long> ids, String column, int limit) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        for (int i = 0; i < ids.size(); i++) {
            args[i] = ids.get(i);
        }
        args[ids.size()] = limit;
        return db.query("SELECT follower_id, followee_id FROM yan_user_follow WHERE "
                        + column + " IN (" + placeholders + ") LIMIT ?",
                (rs, n) -> new FollowEdge(rs.getLong(1), rs.getLong(2)), args);
    }

    private void requireCompleteScan(int rows) {
        if (rows > scanLimit) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    private boolean existsRelation(long followerId, long followeeId) {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM yan_user_follow WHERE follower_id = ? AND followee_id = ?",
                Integer.class,
                followerId,
                followeeId);
        return count != null && count > 0;
    }

    private boolean existsDeliveryTask(String taskKey) {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM yan_notification_delivery_outbox WHERE task_key = ?",
                Integer.class,
                taskKey);
        return count != null && count > 0;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }
}
