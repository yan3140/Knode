package com.knode.community.ops;

import com.knode.community.support.InternalFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CommunityOpsService {
    private static final Set<String> VALID_STATUSES =
            Set.of("PENDING", "PROCESSING", "SUCCEEDED", "FAILED", "DEAD");
    private static final long MAX_OFFSET = 10_000_000L;

    private record AuditRow(
            String replayRequestId,
            String targetScope,
            String serviceSubject,
            String operatorActor,
            String resultStatus,
            long requeuedCount) {}

    private record OutboxStateRow(String status, Timestamp lockedUntil) {}

    private final JdbcTemplate db;
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate readTxTemplate;

    public CommunityOpsService(JdbcTemplate db, PlatformTransactionManager txManager) {
        this.db = db;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate.setTimeout(5);

        this.readTxTemplate = new TransactionTemplate(txManager);
        this.readTxTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTxTemplate.setReadOnly(true);
        this.readTxTemplate.setTimeout(5);
    }

    public Map<String, Object> summary() {
        try {
            return Objects.requireNonNull(readTxTemplate.execute(status -> {
                Map<String, String> inbox = initStates("SUCCEEDED", "IGNORED", "DEAD");
                db.query("SELECT status, COUNT(*) FROM community_event_inbox GROUP BY status", rs -> {
                    String st = rs.getString(1);
                    if (st != null && inbox.containsKey(st)) {
                        inbox.put(st, Long.toString(rs.getLong(2)));
                    }
                });

                Map<String, String> fanout = initStates("PENDING", "PROCESSING", "SUCCEEDED", "FAILED", "DEAD");
                db.query("SELECT status, COUNT(*) FROM yan_notification_outbox GROUP BY status", rs -> {
                    String st = rs.getString(1);
                    if (st != null && fanout.containsKey(st)) {
                        fanout.put(st, Long.toString(rs.getLong(2)));
                    }
                });
                Long fanoutExpired = db.queryForObject(
                        "SELECT COUNT(*) FROM yan_notification_outbox WHERE status = 'PROCESSING' AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP(3))",
                        Long.class);
                fanout.put("expiredProcessing", Long.toString(Objects.requireNonNullElse(fanoutExpired, 0L)));

                Map<String, String> direct = initStates("PENDING", "PROCESSING", "SUCCEEDED", "FAILED", "DEAD");
                db.query("SELECT status, COUNT(*) FROM yan_notification_delivery_outbox GROUP BY status", rs -> {
                    String st = rs.getString(1);
                    if (st != null && direct.containsKey(st)) {
                        direct.put(st, Long.toString(rs.getLong(2)));
                    }
                });
                Long directExpired = db.queryForObject(
                        "SELECT COUNT(*) FROM yan_notification_delivery_outbox WHERE status = 'PROCESSING' AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP(3))",
                        Long.class);
                direct.put("expiredProcessing", Long.toString(Objects.requireNonNullElse(directExpired, 0L)));

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("domain", "notification");
                out.put("status", "AVAILABLE");
                out.put("inbox", inbox);
                out.put("fanoutOutbox", fanout);
                out.put("directOutbox", direct);
                return out;
            }));
        } catch (InternalFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new InternalFailure(503, "DEPENDENCY_UNAVAILABLE", "Community consumer summary unavailable", true);
        }
    }

    public Map<String, Object> events(String pageNumText, String pageSizeText, String statusFilter) {
        int pageNum = parsePositiveInt(pageNumText, 1);
        int pageSize = parsePositiveInt(pageSizeText, 20);
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            throw new InternalFailure(400, "INVALID_ARGUMENT", "Invalid consumer pagination", false);
        }
        long offset = ((long) pageNum - 1L) * pageSize;
        if (offset > MAX_OFFSET) {
            throw new InternalFailure(400, "INVALID_ARGUMENT", "Pagination offset exceeds limit", false);
        }
        String normalizedStatus = null;
        if (statusFilter != null && !statusFilter.isEmpty()) {
            if (!VALID_STATUSES.contains(statusFilter)) {
                throw new InternalFailure(400, "INVALID_ARGUMENT", "Unsupported consumer status filter", false);
            }
            normalizedStatus = statusFilter;
        }

        final String filter = normalizedStatus;
        try {
            return Objects.requireNonNull(readTxTemplate.execute(tx -> {
                String baseCte = """
                        SELECT 'FANOUT' AS queue_name, id AS raw_id, event_id, task_key, 'ArticlePublished' AS event_type,
                               CONCAT('', article_id) AS aggregate_id, status, retry_count, last_error, update_time AS updated_at
                        FROM yan_notification_outbox
                        UNION ALL
                        SELECT 'DIRECT' AS queue_name, id AS raw_id, event_id, task_key, type AS event_type,
                               CASE WHEN comment_id IS NOT NULL THEN CONCAT('', comment_id)
                                    WHEN article_id IS NOT NULL THEN CONCAT('', article_id)
                                    ELSE CONCAT('', actor_id) END AS aggregate_id,
                               status, retry_count, last_error, update_time AS updated_at
                        FROM yan_notification_delivery_outbox
                        UNION ALL
                        SELECT 'INBOX_POISON' AS queue_name, 0 AS raw_id, event_id, event_id AS task_key, event_type,
                               COALESCE(aggregate_id, '') AS aggregate_id, status, 0 AS retry_count, last_error, updated_at
                        FROM community_event_inbox
                        WHERE status = 'DEAD'
                        """;

                Long total;
                List<Map<String, Object>> rows;
                if (filter == null) {
                    total = db.queryForObject("SELECT COUNT(*) FROM (" + baseCte + ") u", Long.class);
                    rows = queryEventRows("SELECT * FROM (" + baseCte + ") u ORDER BY updated_at DESC, task_key ASC LIMIT ? OFFSET ?",
                            pageSize, offset);
                } else {
                    total = db.queryForObject("SELECT COUNT(*) FROM (" + baseCte + ") u WHERE status = ?", Long.class, filter);
                    rows = queryEventRows("SELECT * FROM (" + baseCte + ") u WHERE status = ? ORDER BY updated_at DESC, task_key ASC LIMIT ? OFFSET ?",
                            filter, pageSize, offset);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("rows", rows);
                out.put("total", Long.toString(Objects.requireNonNullElse(total, 0L)));
                return out;
            }));
        } catch (InternalFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new InternalFailure(503, "DEPENDENCY_UNAVAILABLE", "Community consumer events unavailable", true);
        }
    }

    public Map<String, Object> retry(String eventIdOrNull, String replayRequestId, String operatorActor, String serviceSubject) {
        validateReplayRequestId(replayRequestId);
        String safeOperator = normalizeAndValidateOperator(operatorActor);
        String targetScope;
        if (eventIdOrNull == null) {
            targetScope = "ALL";
        } else {
            validateCanonicalUuid(eventIdOrNull);
            targetScope = "EVENT:" + eventIdOrNull;
        }

        String hash = sha256Hex(replayRequestId);
        try {
            return executeRetryWithAudit(hash, replayRequestId, targetScope, eventIdOrNull, serviceSubject, safeOperator);
        } catch (DuplicateKeyException collision) {
            return verifyExistingAuditAfterRace(hash, replayRequestId, targetScope, serviceSubject, safeOperator);
        } catch (InternalFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new InternalFailure(503, "DEPENDENCY_UNAVAILABLE", "Community consumer retry unavailable", true);
        }
    }

    private Map<String, Object> executeRetryWithAudit(
            String hash,
            String replayRequestId,
            String targetScope,
            String eventIdOrNull,
            String serviceSubject,
            String safeOperator) {
        return Objects.requireNonNull(txTemplate.execute(tx -> {
            Map<String, Object> existing = findMatchingAuditOrThrowConflict(hash, replayRequestId, targetScope, serviceSubject, safeOperator);
            if (existing != null) {
                return existing;
            }

            String resultStatus;
            long requeued;
            if (eventIdOrNull == null) {
                requeued = requeueAllEligibleTasks();
                resultStatus = "ACCEPTED";
            } else {
                SingleRetryOutcome outcome = requeueSingleEvent(eventIdOrNull);
                resultStatus = outcome.status();
                requeued = outcome.requeued();
            }

            db.update("""
                    INSERT INTO community_ops_replay_audit(
                        replay_key_sha256, replay_request_id, target_scope, service_subject,
                        operator_actor, result_status, requeued_count, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                    """,
                    hash,
                    replayRequestId,
                    targetScope,
                    serviceSubject,
                    safeOperator,
                    resultStatus,
                    requeued);

            return buildRetryResponse(resultStatus, replayRequestId, requeued);
        }));
    }

    private Map<String, Object> verifyExistingAuditAfterRace(
            String hash,
            String replayRequestId,
            String targetScope,
            String serviceSubject,
            String safeOperator) {
        try {
            Map<String, Object> found = readTxTemplate.execute(
                    tx -> findMatchingAuditOrThrowConflict(hash, replayRequestId, targetScope, serviceSubject, safeOperator));
            if (found == null) {
                throw new InternalFailure(503, "DEPENDENCY_UNAVAILABLE", "Unable to verify concurrent replay state", true);
            }
            return found;
        } catch (InternalFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new InternalFailure(503, "DEPENDENCY_UNAVAILABLE", "Unable to verify concurrent replay state", true);
        }
    }

    private Map<String, Object> findMatchingAuditOrThrowConflict(
            String hash,
            String replayRequestId,
            String targetScope,
            String serviceSubject,
            String safeOperator) {
        List<AuditRow> rows = db.query("""
                SELECT replay_request_id, target_scope, service_subject, operator_actor, result_status, requeued_count
                FROM community_ops_replay_audit WHERE replay_key_sha256 = ?
                """, (rs, n) -> new AuditRow(
                rs.getString(1), rs.getString(2), rs.getString(3),
                Objects.requireNonNullElse(rs.getString(4), ""), rs.getString(5), rs.getLong(6)),
                hash);
        if (rows.isEmpty()) {
            return null;
        }
        AuditRow row = rows.get(0);
        if (!row.replayRequestId().equals(replayRequestId)
                || !row.targetScope().equals(targetScope)
                || !row.serviceSubject().equals(serviceSubject)
                || !row.operatorActor().equals(safeOperator)) {
            throw new InternalFailure(409, "IDEMPOTENCY_CONFLICT", "Replay request id already bound to different target or actor", false);
        }
        return buildRetryResponse(row.resultStatus(), replayRequestId, row.requeuedCount());
    }

    private long requeueAllEligibleTasks() {
        int fanoutExpired = db.update("""
                UPDATE yan_notification_outbox
                SET status = 'PENDING', retry_count = 0, next_retry_at = CURRENT_TIMESTAMP(3),
                    locked_until = NULL, lease_token = NULL, dead_letter_at = NULL, last_error = NULL,
                    update_time = CURRENT_TIMESTAMP(3)
                WHERE status = 'PROCESSING' AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP(3))
                """);
        int fanoutFailed = db.update("""
                UPDATE yan_notification_outbox
                SET status = 'PENDING', retry_count = 0, next_retry_at = CURRENT_TIMESTAMP(3),
                    locked_until = NULL, lease_token = NULL, dead_letter_at = NULL, last_error = NULL,
                    update_time = CURRENT_TIMESTAMP(3)
                WHERE status IN ('FAILED', 'DEAD')
                   OR (status = 'PENDING' AND (retry_count > 0 OR next_retry_at > CURRENT_TIMESTAMP(3)))
                """);
        int directExpired = db.update("""
                UPDATE yan_notification_delivery_outbox
                SET status = 'PENDING', retry_count = 0, next_retry_at = CURRENT_TIMESTAMP(3),
                    locked_until = NULL, lease_token = NULL, dead_letter_at = NULL, last_error = NULL,
                    update_time = CURRENT_TIMESTAMP(3)
                WHERE status = 'PROCESSING' AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP(3))
                """);
        int directFailed = db.update("""
                UPDATE yan_notification_delivery_outbox
                SET status = 'PENDING', retry_count = 0, next_retry_at = CURRENT_TIMESTAMP(3),
                    locked_until = NULL, lease_token = NULL, dead_letter_at = NULL, last_error = NULL,
                    update_time = CURRENT_TIMESTAMP(3)
                WHERE status IN ('FAILED', 'DEAD')
                   OR (status = 'PENDING' AND (retry_count > 0 OR next_retry_at > CURRENT_TIMESTAMP(3)))
                """);
        return (long) fanoutExpired + fanoutFailed + directExpired + directFailed;
    }

    private record SingleRetryOutcome(String status, long requeued) {}

    private SingleRetryOutcome requeueSingleEvent(String eventId) {
        Timestamp now = db.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);

        List<OutboxStateRow> fanout = db.query(
                "SELECT status, locked_until FROM yan_notification_outbox WHERE event_id = ? FOR UPDATE",
                (rs, n) -> new OutboxStateRow(rs.getString(1), rs.getTimestamp(2)),
                eventId);
        if (!fanout.isEmpty()) {
            OutboxStateRow row = fanout.get(0);
            if ("PROCESSING".equals(row.status()) && row.lockedUntil() != null && now != null && !row.lockedUntil().before(now)) {
                throw new InternalFailure(409, "STATE_CONFLICT", "Fanout task currently holds an active worker lease", false);
            }
            if ("SUCCEEDED".equals(row.status())) {
                return new SingleRetryOutcome("NOOP", 0L);
            }
            int updated = db.update("""
                    UPDATE yan_notification_outbox
                    SET status = 'PENDING', retry_count = 0, next_retry_at = CURRENT_TIMESTAMP(3),
                        locked_until = NULL, lease_token = NULL, dead_letter_at = NULL, last_error = NULL,
                        update_time = CURRENT_TIMESTAMP(3)
                    WHERE event_id = ?
                    """, eventId);
            return new SingleRetryOutcome("ACCEPTED", updated);
        }

        List<OutboxStateRow> direct = db.query(
                "SELECT status, locked_until FROM yan_notification_delivery_outbox WHERE event_id = ? FOR UPDATE",
                (rs, n) -> new OutboxStateRow(rs.getString(1), rs.getTimestamp(2)),
                eventId);
        if (!direct.isEmpty()) {
            OutboxStateRow row = direct.get(0);
            if ("PROCESSING".equals(row.status()) && row.lockedUntil() != null && now != null && !row.lockedUntil().before(now)) {
                throw new InternalFailure(409, "STATE_CONFLICT", "Delivery task currently holds an active worker lease", false);
            }
            if ("SUCCEEDED".equals(row.status())) {
                return new SingleRetryOutcome("NOOP", 0L);
            }
            int updated = db.update("""
                    UPDATE yan_notification_delivery_outbox
                    SET status = 'PENDING', retry_count = 0, next_retry_at = CURRENT_TIMESTAMP(3),
                        locked_until = NULL, lease_token = NULL, dead_letter_at = NULL, last_error = NULL,
                        update_time = CURRENT_TIMESTAMP(3)
                    WHERE event_id = ?
                    """, eventId);
            return new SingleRetryOutcome("ACCEPTED", updated);
        }

        List<String> inboxStatus = db.queryForList(
                "SELECT status FROM community_event_inbox WHERE event_id = ? FOR UPDATE",
                String.class,
                eventId);
        if (!inboxStatus.isEmpty()) {
            String st = inboxStatus.get(0);
            if ("DEAD".equals(st)) {
                throw new InternalFailure(409, "STATE_CONFLICT", "Poison or unsupported event cannot be replayed without producer correction", false);
            }
            return new SingleRetryOutcome("NOOP", 0L);
        }

        throw new InternalFailure(404, "RESOURCE_NOT_FOUND", "Event not found in community consumer", false);
    }

    private List<Map<String, Object>> queryEventRows(String sql, Object... args) {
        List<Map<String, Object>> list = new ArrayList<>();
        db.query(sql, rs -> {
            String queue = rs.getString("queue_name");
            long rawId = rs.getLong("raw_id");
            String eventId = rs.getString("event_id");
            if (eventId == null || eventId.isBlank()) {
                eventId = UUID.nameUUIDFromBytes(("LEGACY_" + queue + ":" + rawId).getBytes(StandardCharsets.UTF_8)).toString();
            }
            Timestamp ts = rs.getTimestamp("updated_at");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", eventId);
            item.put("queue", queue);
            item.put("taskKey", rs.getString("task_key"));
            item.put("eventType", rs.getString("event_type"));
            item.put("aggregateId", rs.getString("aggregate_id"));
            item.put("status", rs.getString("status"));
            item.put("retryCount", Integer.toString(rs.getInt("retry_count")));
            item.put("lastError", rs.getString("last_error"));
            item.put("updatedAt", ts == null ? null : ts.toInstant().toString());
            list.add(item);
        }, args);
        return list;
    }

    private static Map<String, Object> buildRetryResponse(String status, String replayRequestId, long requeued) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("replayRequestId", replayRequestId);
        out.put("requeued", Long.toString(Math.max(0L, requeued)));
        return out;
    }

    private static Map<String, String> initStates(String... names) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : names) {
            out.put(name, "0");
        }
        return out;
    }

    private static int parsePositiveInt(String text, int fallback) {
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        if (!text.matches("[1-9][0-9]{0,8}")) {
            throw new InternalFailure(400, "INVALID_ARGUMENT", "Invalid numeric query parameter", false);
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new InternalFailure(400, "INVALID_ARGUMENT", "Invalid numeric query parameter", false);
        }
    }

    private static void validateReplayRequestId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new InternalFailure(400, "INVALID_ARGUMENT", "Invalid replayRequestId", false);
        }
    }

    private static String normalizeAndValidateOperator(String operator) {
        if (operator == null || operator.isEmpty()) {
            return "";
        }
        if (!operator.matches("[A-Za-z0-9:._-]{1,128}")) {
            throw new InternalFailure(400, "INVALID_ARGUMENT", "Invalid operator identifier", false);
        }
        return operator;
    }

    private static void validateCanonicalUuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new InternalFailure(400, "INVALID_ARGUMENT", "Invalid eventId UUID", false);
            }
        } catch (IllegalArgumentException invalid) {
            throw new InternalFailure(400, "INVALID_ARGUMENT", "Invalid eventId UUID", false);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
