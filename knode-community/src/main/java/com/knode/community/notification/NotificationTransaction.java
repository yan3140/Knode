package com.knode.community.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationTransaction {
    public record RawNotificationRow(
            long id,
            String type,
            String content,
            int isRead,
            Instant createTime,
            Long actorId,
            Long articleId,
            Long commentId,
            Long rootCommentId) {}

    public record InboxPage(long total, List<RawNotificationRow> rows) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NotificationSummaryDto(
            String unreadCount,
            String latestNotificationId,
            String asOf) {}

    private static final String INBOX_UNION_SQL = """
            SELECT n.id AS notif_id,
                   n.type AS notif_type,
                   n.content AS notif_content,
                   n.is_read AS notif_read,
                   n.create_time AS notif_time,
                   n.actor_id AS actor_id,
                   n.article_id AS article_id,
                   n.comment_id AS comment_id,
                   n.root_comment_id AS root_comment_id
            FROM yan_user_notification n
            WHERE n.receiver_id = ?
            UNION ALL
            SELECT -e.id AS notif_id,
                   'ARTICLE_PUBLISHED' AS notif_type,
                   NULL AS notif_content,
                   1 AS notif_read,
                   e.create_time AS notif_time,
                   e.author_id AS actor_id,
                   e.article_id AS article_id,
                   NULL AS comment_id,
                   NULL AS root_comment_id
            FROM yan_author_publish_event e
            INNER JOIN yan_user_follow f
                ON f.followee_id = e.author_id AND f.follower_id = ?
            WHERE e.create_time >= f.create_time
              AND NOT EXISTS (
                  SELECT 1 FROM community_publish_event_dismissal d
                  WHERE d.receiver_id = ? AND d.publish_event_id = e.id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM community_article_projection p
                  WHERE p.article_id = e.article_id AND p.visibility <> 'PUBLIC'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM yan_user_notification n2
                  WHERE n2.receiver_id = ? AND n2.article_id = e.article_id AND n2.type = 'ARTICLE_PUBLISHED'
              )
            """;

    private final JdbcTemplate db;

    public NotificationTransaction(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public InboxPage selectInbox(long receiverId, int limit, long offset) {
        Long total = db.queryForObject(
                "SELECT COUNT(*) FROM (" + INBOX_UNION_SQL + ") inbox_count",
                Long.class,
                receiverId, receiverId, receiverId, receiverId);
        long safeTotal = Objects.requireNonNullElse(total, 0L);
        if (safeTotal == 0L || offset >= safeTotal) {
            return new InboxPage(safeTotal, List.of());
        }
        List<RawNotificationRow> rows = db.query(
                "SELECT notif_id, notif_type, notif_content, notif_read, notif_time, actor_id, article_id, comment_id, root_comment_id "
                        + "FROM (" + INBOX_UNION_SQL + ") inbox_page "
                        + "ORDER BY notif_read ASC, notif_time DESC, notif_id DESC LIMIT ? OFFSET ?",
                NotificationTransaction::mapRow,
                receiverId, receiverId, receiverId, receiverId, limit, offset);
        return new InboxPage(safeTotal, rows);
    }

    @Transactional(readOnly = true, timeout = 5)
    public long countUnread(long receiverId) {
        Long count = db.queryForObject(
                "SELECT COUNT(*) FROM yan_user_notification WHERE receiver_id = ? AND is_read = 0",
                Long.class,
                receiverId);
        return Objects.requireNonNullElse(count, 0L);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void markRead(long receiverId, long notificationId) {
        if (notificationId < 0L) {
            // Read-fanout publish stream items are already isRead=1 by definition.
            return;
        }
        db.update(
                "UPDATE yan_user_notification SET is_read = 1, update_time = CURRENT_TIMESTAMP(3) "
                        + "WHERE id = ? AND receiver_id = ? AND is_read = 0",
                notificationId,
                receiverId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void markAllRead(long receiverId) {
        db.update(
                "UPDATE yan_user_notification SET is_read = 1, update_time = CURRENT_TIMESTAMP(3) "
                        + "WHERE receiver_id = ? AND is_read = 0",
                receiverId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void delete(long receiverId, long notificationId) {
        if (notificationId > 0L) {
            db.update(
                    "DELETE FROM yan_user_notification WHERE id = ? AND receiver_id = ?",
                    notificationId,
                    receiverId);
            return;
        }
        long publishEventId = Math.negateExact(notificationId);
        Integer alreadyDismissed = db.queryForObject(
                "SELECT COUNT(*) FROM community_publish_event_dismissal WHERE receiver_id = ? AND publish_event_id = ?",
                Integer.class,
                receiverId,
                publishEventId);
        if (alreadyDismissed != null && alreadyDismissed > 0) {
            return;
        }
        Integer eligible = db.queryForObject("""
                SELECT COUNT(*) FROM yan_author_publish_event e
                INNER JOIN yan_user_follow f
                    ON f.followee_id = e.author_id AND f.follower_id = ?
                WHERE e.id = ? AND e.create_time >= f.create_time
                """,
                Integer.class,
                receiverId,
                publishEventId);
        if (eligible == null || eligible < 1) {
            return;
        }
        try {
            db.update(
                    "INSERT INTO community_publish_event_dismissal(receiver_id, publish_event_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP(3))",
                    receiverId,
                    publishEventId);
        } catch (DuplicateKeyException ignored) {
            // Concurrent dismissal of the same negative-ID stream entry is idempotent.
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public NotificationSummaryDto querySummary(long receiverId, Instant asOf) {
        Long unread = db.queryForObject(
                "SELECT COUNT(*) FROM yan_user_notification WHERE receiver_id = ? AND is_read = 0",
                Long.class,
                receiverId);
        long safeUnread = Objects.requireNonNullElse(unread, 0L);
        if (safeUnread < 0L) {
            throw new IllegalStateException("Negative unread count");
        }
        List<Long> latest = db.query(
                "SELECT notif_id FROM (" + INBOX_UNION_SQL + ") latest_box ORDER BY notif_time DESC, notif_id DESC LIMIT 1",
                (rs, n) -> rs.getLong(1),
                receiverId, receiverId, receiverId, receiverId);
        String latestId = latest.isEmpty() ? null : Long.toString(latest.get(0));
        return new NotificationSummaryDto(Long.toString(safeUnread), latestId, asOf.toString());
    }

    private static RawNotificationRow mapRow(ResultSet rs, int index) throws SQLException {
        long id = rs.getLong("notif_id");
        String type = rs.getString("notif_type");
        String content = rs.getString("notif_content");
        int isRead = rs.getInt("notif_read") == 0 ? 0 : 1;
        Timestamp ts = rs.getTimestamp("notif_time");
        Instant createTime = ts == null ? Instant.EPOCH : ts.toInstant();
        Long actorId = nullableLong(rs, "actor_id");
        Long articleId = nullableLong(rs, "article_id");
        Long commentId = nullableLong(rs, "comment_id");
        Long rootCommentId = nullableLong(rs, "root_comment_id");
        return new RawNotificationRow(id, type, content, isRead, createTime, actorId, articleId, commentId, rootCommentId);
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long val = rs.getLong(col);
        return rs.wasNull() ? null : val;
    }
}
