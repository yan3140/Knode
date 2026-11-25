package com.knode.community.consumer;

import com.knode.observability.EventTraceContext;
import io.opentelemetry.api.trace.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationDeliveryWorker {
    public record ClaimedDirectTask(
            long id,
            String taskKey,
            long receiverId,
            long actorId,
            Long articleId,
            Long commentId,
            Long rootCommentId,
            String type,
            String content,
            int retryCount,
            int maxRetry,
            String leaseToken,
            String traceparent,
            int attempt) {}

    public record ClaimedFanoutTask(
            long id,
            long articleId,
            long authorId,
            String taskKey,
            long cursorFollowerId,
            int retryCount,
            int maxRetry,
            String leaseToken,
            String traceparent,
            int attempt) {}

    private static final class StaleLeaseException extends RuntimeException {
        StaleLeaseException() {
            super("Task lease is no longer owned by this worker");
        }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate txTemplate;
    private final EventTraceContext eventTraces;
    private final int directPollBatch;
    private final int fanoutPollBatch;
    private final int fanoutFollowerBatch;
    private final int leaseSeconds;
    private final int largeAuthorThreshold;
    private final int baseRetryDelaySeconds;
    private final int maxRetryDelaySeconds;

    public NotificationDeliveryWorker(
            JdbcTemplate db,
            PlatformTransactionManager txManager,
            ObjectProvider<EventTraceContext> traceProvider,
            @Value("${KNODE_COMMUNITY_DIRECT_POLL_BATCH:50}") int directPollBatch,
            @Value("${KNODE_COMMUNITY_FANOUT_POLL_BATCH:20}") int fanoutPollBatch,
            @Value("${KNODE_COMMUNITY_FANOUT_FOLLOWER_BATCH:500}") int fanoutFollowerBatch,
            @Value("${KNODE_COMMUNITY_TASK_LEASE_SECONDS:30}") int leaseSeconds,
            @Value("${KNODE_COMMUNITY_LARGE_AUTHOR_THRESHOLD:10000}") int largeAuthorThreshold,
            @Value("${KNODE_COMMUNITY_RETRY_DELAY_SECONDS:2}") int baseRetryDelaySeconds,
            @Value("${KNODE_COMMUNITY_MAX_RETRY_DELAY_SECONDS:300}") int maxRetryDelaySeconds) {
        if (directPollBatch < 1 || directPollBatch > 200
                || fanoutPollBatch < 1 || fanoutPollBatch > 100
                || fanoutFollowerBatch < 1 || fanoutFollowerBatch > 2000
                || leaseSeconds < 1 || leaseSeconds > 600
                || largeAuthorThreshold < 1
                || baseRetryDelaySeconds < 1
                || maxRetryDelaySeconds < baseRetryDelaySeconds) {
            throw new IllegalArgumentException("Invalid community delivery worker bounds");
        }
        this.db = db;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate.setTimeout(5);
        this.eventTraces = traceProvider == null ? null : traceProvider.getIfAvailable();
        this.directPollBatch = directPollBatch;
        this.fanoutPollBatch = fanoutPollBatch;
        this.fanoutFollowerBatch = fanoutFollowerBatch;
        this.leaseSeconds = leaseSeconds;
        this.largeAuthorThreshold = largeAuthorThreshold;
        this.baseRetryDelaySeconds = baseRetryDelaySeconds;
        this.maxRetryDelaySeconds = maxRetryDelaySeconds;
    }

    public int processDirectBatch() {
        List<ClaimedDirectTask> claimed = claimDirectTasks();
        int completed = 0;
        for (ClaimedDirectTask task : claimed) {
            if (executeClaimedDirect(task)) {
                completed++;
            }
        }
        return completed;
    }

    public int processFanoutBatch() {
        List<ClaimedFanoutTask> claimed = claimFanoutTasks();
        int progressed = 0;
        for (ClaimedFanoutTask task : claimed) {
            if (executeClaimedFanout(task)) {
                progressed++;
            }
        }
        return progressed;
    }

    public List<ClaimedDirectTask> claimDirectTasks() {
        return Objects.requireNonNullElse(txTemplate.execute(status -> {
            db.update("""
                    UPDATE yan_notification_delivery_outbox
                    SET status = 'PENDING', locked_until = NULL, lease_token = NULL, update_time = CURRENT_TIMESTAMP(3)
                    WHERE status = 'PROCESSING' AND locked_until < CURRENT_TIMESTAMP(3)
                    """);
            List<Long> candidates = db.queryForList("""
                    SELECT id FROM yan_notification_delivery_outbox
                    WHERE status = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP(3)
                    ORDER BY next_retry_at ASC, id ASC LIMIT ?
                    """, Long.class, directPollBatch);
            List<ClaimedDirectTask> out = new ArrayList<>();
            for (Long id : candidates) {
                String token = UUID.randomUUID().toString();
                int updated = db.update("""
                        UPDATE yan_notification_delivery_outbox
                        SET status = 'PROCESSING',
                            lease_token = ?,
                            locked_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)),
                            process_count = process_count + 1,
                            last_process_at = CURRENT_TIMESTAMP(3),
                            update_time = CURRENT_TIMESTAMP(3)
                        WHERE id = ? AND status = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP(3)
                        """, token, leaseSeconds, id);
                if (updated == 1) {
                    ClaimedDirectTask loaded = loadDirectTask(id, token);
                    if (loaded != null) {
                        out.add(loaded);
                    }
                }
            }
            return out;
        }), List.of());
    }

    public boolean executeClaimedDirect(ClaimedDirectTask task) {
        try {
            txTemplate.executeWithoutResult(status -> {
                List<Long> locked = db.queryForList(
                        "SELECT id FROM yan_notification_delivery_outbox WHERE id = ? AND status = 'PROCESSING' AND lease_token = ? FOR UPDATE",
                        Long.class,
                        task.id(),
                        task.leaseToken());
                if (locked.size() != 1) {
                    throw new StaleLeaseException();
                }
                insertNotificationIdempotently(
                        task.taskKey(),
                        task.receiverId(),
                        task.actorId(),
                        task.articleId(),
                        task.commentId(),
                        task.rootCommentId(),
                        task.type(),
                        task.content());
                int marked = db.update("""
                        UPDATE yan_notification_delivery_outbox
                        SET status = CASE WHEN rerun_requested = 1 THEN 'PENDING' ELSE 'SUCCEEDED' END,
                            locked_until = NULL,
                            lease_token = NULL,
                            last_error = NULL,
                            dead_letter_at = NULL,
                            next_retry_at = CASE WHEN rerun_requested = 1 THEN CURRENT_TIMESTAMP(3) ELSE next_retry_at END,
                            rerun_requested = 0,
                            update_time = CURRENT_TIMESTAMP(3)
                        WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                        """, task.id(), task.leaseToken());
                if (marked != 1) {
                    throw new StaleLeaseException();
                }
            });
            recordDeliverySpan(task.traceparent(), "community.notification.direct_deliver", task.attempt());
            return true;
        } catch (StaleLeaseException stale) {
            return false;
        } catch (RuntimeException failure) {
            markDirectRetryOrDead(task, safeReason(failure));
            return false;
        }
    }

    public List<ClaimedFanoutTask> claimFanoutTasks() {
        return Objects.requireNonNullElse(txTemplate.execute(status -> {
            db.update("""
                    UPDATE yan_notification_outbox
                    SET status = 'PENDING', locked_until = NULL, lease_token = NULL, update_time = CURRENT_TIMESTAMP(3)
                    WHERE status = 'PROCESSING' AND locked_until < CURRENT_TIMESTAMP(3)
                    """);
            List<Long> candidates = db.queryForList("""
                    SELECT id FROM yan_notification_outbox
                    WHERE status = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP(3)
                    ORDER BY next_retry_at ASC, id ASC LIMIT ?
                    """, Long.class, fanoutPollBatch);
            List<ClaimedFanoutTask> out = new ArrayList<>();
            for (Long id : candidates) {
                String token = UUID.randomUUID().toString();
                int updated = db.update("""
                        UPDATE yan_notification_outbox
                        SET status = 'PROCESSING',
                            lease_token = ?,
                            locked_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)),
                            process_count = process_count + 1,
                            last_process_at = CURRENT_TIMESTAMP(3),
                            update_time = CURRENT_TIMESTAMP(3)
                        WHERE id = ? AND status = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP(3)
                        """, token, leaseSeconds, id);
                if (updated == 1) {
                    ClaimedFanoutTask loaded = loadFanoutTask(id, token);
                    if (loaded != null) {
                        out.add(loaded);
                    }
                }
            }
            return out;
        }), List.of());
    }

    public boolean executeClaimedFanout(ClaimedFanoutTask task) {
        try {
            txTemplate.executeWithoutResult(status -> {
                List<Long> currentCursorList = db.queryForList(
                        "SELECT cursor_follower_id FROM yan_notification_outbox WHERE id = ? AND status = 'PROCESSING' AND lease_token = ? FOR UPDATE",
                        Long.class,
                        task.id(),
                        task.leaseToken());
                if (currentCursorList.size() != 1) {
                    throw new StaleLeaseException();
                }
                long cursor = currentCursorList.get(0) == null ? 0L : currentCursorList.get(0);

                if (cursor == 0L) {
                    Long followerCount = db.queryForObject(
                            "SELECT COUNT(*) FROM yan_user_follow WHERE followee_id = ?",
                            Long.class,
                            task.authorId());
                    if (Objects.requireNonNullElse(followerCount, 0L) >= largeAuthorThreshold) {
                        insertAuthorPublishEventIdempotently(task.authorId(), task.articleId());
                        completeFanoutTask(task.id(), task.leaseToken());
                        return;
                    }
                }

                List<Long> followers = db.queryForList("""
                        SELECT follower_id FROM yan_user_follow
                        WHERE followee_id = ? AND follower_id > ?
                        ORDER BY follower_id ASC LIMIT ?
                        """, Long.class, task.authorId(), cursor, fanoutFollowerBatch);

                if (followers.isEmpty()) {
                    completeFanoutTask(task.id(), task.leaseToken());
                    return;
                }

                for (Long followerId : followers) {
                    if (followerId != null && followerId != task.authorId()) {
                        String dedupeKey = "ARTICLE_PUBLISHED:" + task.articleId() + ":" + followerId;
                        insertNotificationIdempotently(
                                dedupeKey,
                                followerId,
                                task.authorId(),
                                task.articleId(),
                                null,
                                null,
                                "ARTICLE_PUBLISHED",
                                null);
                    }
                }

                long lastFollower = followers.get(followers.size() - 1);
                if (followers.size() < fanoutFollowerBatch) {
                    completeFanoutTask(task.id(), task.leaseToken());
                } else {
                    int advanced = db.update("""
                            UPDATE yan_notification_outbox
                            SET status = 'PENDING',
                                cursor_follower_id = ?,
                                locked_until = NULL,
                                lease_token = NULL,
                                next_retry_at = CURRENT_TIMESTAMP(3),
                                update_time = CURRENT_TIMESTAMP(3)
                            WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                            """, lastFollower, task.id(), task.leaseToken());
                    if (advanced != 1) {
                        throw new StaleLeaseException();
                    }
                }
            });
            recordDeliverySpan(task.traceparent(), "community.notification.fanout_batch", task.attempt());
            return true;
        } catch (StaleLeaseException stale) {
            return false;
        } catch (RuntimeException failure) {
            markFanoutRetryOrDead(task, safeReason(failure));
            return false;
        }
    }

    private void completeFanoutTask(long id, String leaseToken) {
        int updated = db.update("""
                UPDATE yan_notification_outbox
                SET status = CASE WHEN rerun_requested = 1 THEN 'PENDING' ELSE 'SUCCEEDED' END,
                    cursor_follower_id = CASE WHEN rerun_requested = 1 THEN 0 ELSE cursor_follower_id END,
                    locked_until = NULL,
                    lease_token = NULL,
                    last_error = NULL,
                    dead_letter_at = NULL,
                    next_retry_at = CASE WHEN rerun_requested = 1 THEN CURRENT_TIMESTAMP(3) ELSE next_retry_at END,
                    rerun_requested = 0,
                    update_time = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, id, leaseToken);
        if (updated != 1) {
            throw new StaleLeaseException();
        }
    }

    private void insertAuthorPublishEventIdempotently(long authorId, long articleId) {
        Integer exists = db.queryForObject(
                "SELECT COUNT(*) FROM yan_author_publish_event WHERE article_id = ?",
                Integer.class,
                articleId);
        if (exists != null && exists > 0) {
            return;
        }
        try {
            db.update(
                    "INSERT INTO yan_author_publish_event(author_id, article_id, create_time) VALUES (?, ?, CURRENT_TIMESTAMP(3))",
                    authorId,
                    articleId);
        } catch (DuplicateKeyException duplicate) {
            Integer recheck = db.queryForObject(
                    "SELECT COUNT(*) FROM yan_author_publish_event WHERE article_id = ?",
                    Integer.class,
                    articleId);
            if (recheck == null || recheck < 1) {
                throw duplicate;
            }
        }
    }

    private void insertNotificationIdempotently(
            String dedupeKey,
            long receiverId,
            long actorId,
            Long articleId,
            Long commentId,
            Long rootCommentId,
            String type,
            String content) {
        Integer exists = db.queryForObject(
                "SELECT COUNT(*) FROM yan_user_notification WHERE dedupe_key = ?",
                Integer.class,
                dedupeKey);
        if (exists != null && exists > 0) {
            return;
        }
        try {
            db.update("""
                    INSERT INTO yan_user_notification(
                        dedupe_key, receiver_id, actor_id, article_id, comment_id, root_comment_id,
                        type, content, is_read, create_time, update_time
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                    """,
                    dedupeKey,
                    receiverId,
                    actorId,
                    articleId,
                    commentId,
                    rootCommentId,
                    type,
                    content);
        } catch (DuplicateKeyException duplicate) {
            Integer recheck = db.queryForObject(
                    "SELECT COUNT(*) FROM yan_user_notification WHERE dedupe_key = ?",
                    Integer.class,
                    dedupeKey);
            if (recheck == null || recheck < 1) {
                throw duplicate;
            }
        }
    }

    private void markDirectRetryOrDead(ClaimedDirectTask task, String reason) {
        int delay = nextDelaySeconds(task.retryCount());
        txTemplate.executeWithoutResult(status -> db.update("""
                UPDATE yan_notification_delivery_outbox
                SET status = CASE WHEN retry_count + 1 >= max_retry THEN 'DEAD' ELSE 'PENDING' END,
                    next_retry_at = CASE WHEN retry_count + 1 >= max_retry THEN next_retry_at ELSE TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)) END,
                    locked_until = NULL,
                    lease_token = NULL,
                    last_error = ?,
                    dead_letter_at = CASE WHEN retry_count + 1 >= max_retry THEN CURRENT_TIMESTAMP(3) ELSE dead_letter_at END,
                    retry_count = retry_count + 1,
                    update_time = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, delay, reason, task.id(), task.leaseToken()));
    }

    private void markFanoutRetryOrDead(ClaimedFanoutTask task, String reason) {
        int delay = nextDelaySeconds(task.retryCount());
        txTemplate.executeWithoutResult(status -> db.update("""
                UPDATE yan_notification_outbox
                SET status = CASE WHEN retry_count + 1 >= max_retry THEN 'DEAD' ELSE 'PENDING' END,
                    next_retry_at = CASE WHEN retry_count + 1 >= max_retry THEN next_retry_at ELSE TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)) END,
                    locked_until = NULL,
                    lease_token = NULL,
                    last_error = ?,
                    dead_letter_at = CASE WHEN retry_count + 1 >= max_retry THEN CURRENT_TIMESTAMP(3) ELSE dead_letter_at END,
                    retry_count = retry_count + 1,
                    update_time = CURRENT_TIMESTAMP(3)
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, delay, reason, task.id(), task.leaseToken()));
    }

    private ClaimedDirectTask loadDirectTask(long id, String expectedLeaseToken) {
        List<ClaimedDirectTask> rows = db.query("""
                SELECT id, task_key, receiver_id, actor_id, article_id, comment_id, root_comment_id,
                       type, content, retry_count, max_retry, lease_token, traceparent, process_count
                FROM yan_notification_delivery_outbox
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, (rs, n) -> {
            long art = rs.getLong(5);
            Long articleId = rs.wasNull() ? null : art;
            long cmt = rs.getLong(6);
            Long commentId = rs.wasNull() ? null : cmt;
            long root = rs.getLong(7);
            Long rootId = rs.wasNull() ? null : root;
            return new ClaimedDirectTask(
                    rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                    articleId, commentId, rootId, rs.getString(8), rs.getString(9),
                    rs.getInt(10), rs.getInt(11), rs.getString(12), rs.getString(13), Math.max(1, rs.getInt(14)));
        }, id, expectedLeaseToken);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClaimedFanoutTask loadFanoutTask(long id, String expectedLeaseToken) {
        List<ClaimedFanoutTask> rows = db.query("""
                SELECT id, article_id, author_id, task_key, cursor_follower_id,
                       retry_count, max_retry, lease_token, traceparent, process_count
                FROM yan_notification_outbox
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, (rs, n) -> new ClaimedFanoutTask(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9), Math.max(1, rs.getInt(10))),
                id, expectedLeaseToken);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int nextDelaySeconds(int currentRetryCount) {
        int shift = Math.min(Math.max(0, currentRetryCount), 10);
        long computed = (long) baseRetryDelaySeconds * (1L << shift);
        return (int) Math.min(computed, maxRetryDelaySeconds);
    }

    private void recordDeliverySpan(String traceparent, String opName, int attempt) {
        if (eventTraces == null) {
            return;
        }
        EventTraceContext.Saved saved = traceparent == null
                ? null
                : eventTraces.fromStored(traceparent, null).orElse(null);
        Span span = eventTraces.startConsumer(saved, opName, Math.max(1, attempt));
        span.end();
    }

    private static String safeReason(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name.isBlank() ? "DELIVERY_ERROR" : (name.length() <= 128 ? name : name.substring(0, 128));
    }
}
