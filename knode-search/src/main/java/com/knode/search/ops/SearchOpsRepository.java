package com.knode.search.ops;

import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
public class SearchOpsRepository {
    private final JdbcTemplate jdbcTemplate;
    private final SearchConsumerRepository consumerRepository;

    public SearchOpsRepository(
            @Qualifier("searchConsumerJdbcTemplate") JdbcTemplate jdbcTemplate,
            SearchConsumerRepository consumerRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.consumerRepository = consumerRepository;
    }

    public OpsSummaryDbSnapshot loadSummarySnapshot(String aliasName, String defaultPhysicalIndex, long nowMs) {
        Optional<IndexGenerationState> maybeGen = consumerRepository.findGenerationState(aliasName);
        long activeGeneration = maybeGen.map(IndexGenerationState::activeGeneration)
                .orElse(SearchConsumerRepository.DEFAULT_GENERATION);
        String activePhysicalIndex = maybeGen.map(IndexGenerationState::activePhysicalIndex)
                .orElse(defaultPhysicalIndex);
        String cutoverState = maybeGen.map(g -> g.cutoverState().name()).orElse("NORMAL");
        String genCacheStatus = maybeGen.map(g -> g.activeCacheInvalidationStatus().name()).orElse("DONE");
        int genCacheAttempts = maybeGen.map(IndexGenerationState::activeCacheInvalidationAttempts).orElse(0);

        Map<String, Long> inboxCounts = new LinkedHashMap<>();
        inboxCounts.put("APPLIED", 0L);
        inboxCounts.put("STALE_IGNORED", 0L);
        inboxCounts.put("IGNORED", 0L);
        inboxCounts.put("PROCESSING", 0L);
        jdbcTemplate.query(
                "SELECT status, COUNT(*) AS cnt FROM search_event_inbox GROUP BY status",
                rs -> {
                    String status = rs.getString("status");
                    long cnt = rs.getLong("cnt");
                    if (status != null) {
                        inboxCounts.put(status, cnt);
                    }
                }
        );
        long protocolFailed = queryCount("SELECT COUNT(*) FROM search_event_failure");
        inboxCounts.put("PROTOCOL_FAILED", protocolFailed);

        Map<String, Long> taskCounts = new LinkedHashMap<>();
        taskCounts.put("PENDING", 0L);
        taskCounts.put("LEASED", 0L);
        taskCounts.put("DONE", 0L);
        taskCounts.put("DEAD", 0L);
        jdbcTemplate.query(
                "SELECT status, COUNT(*) AS cnt FROM search_index_task WHERE index_generation = ? GROUP BY status",
                rs -> {
                    String status = rs.getString("status");
                    long cnt = rs.getLong("cnt");
                    if (status != null) {
                        taskCounts.put(status, cnt);
                    }
                },
                activeGeneration
        );
        long expiredLeased = queryCount(
                "SELECT COUNT(*) FROM search_index_task WHERE index_generation = ? AND status = 'LEASED' AND lease_until_ms IS NOT NULL AND lease_until_ms <= ?",
                activeGeneration,
                nowMs
        );
        long taskCachePending = queryCount(
                "SELECT COUNT(*) FROM search_index_task WHERE cache_invalidation_status = 'PENDING'"
        );

        ActiveRebuildSummary activeRebuild = loadActiveRebuild(aliasName);
        CompletedRebuildSummary lastCompletedRebuild = loadLastCompletedRebuild(aliasName);

        return new OpsSummaryDbSnapshot(
                aliasName,
                activeGeneration,
                activePhysicalIndex,
                cutoverState,
                inboxCounts,
                taskCounts,
                expiredLeased,
                taskCachePending,
                genCacheStatus,
                genCacheAttempts,
                activeRebuild,
                lastCompletedRebuild
        );
    }

    public EventPageResult listConsumerEvents(
            long activeGeneration,
            String statusFilter,
            int pageNum,
            int pageSize) {
        int offset = (pageNum - 1) * pageSize;
        if ("PROTOCOL_FAILED".equals(statusFilter)) {
            return queryProtocolFailuresOnly(pageSize, offset);
        }
        if ("PENDING".equals(statusFilter) || "LEASED".equals(statusFilter) || "DONE".equals(statusFilter)) {
            return queryIndexTasksOnly(activeGeneration, statusFilter, pageSize, offset);
        }
        // Combined view for ALL, FAILED, or DEAD (includes both search_index_task and search_event_failure)
        return queryCombinedTasksAndFailures(activeGeneration, statusFilter, pageSize, offset);
    }

    private EventPageResult queryProtocolFailuresOnly(int limit, int offset) {
        long total = queryCount("SELECT COUNT(*) FROM search_event_failure");
        List<ConsumerEventRow> rows = jdbcTemplate.query(
                """
                SELECT failure_key, event_id, stream_record_id, error_code, error_message, recorded_at_ms
                FROM search_event_failure
                ORDER BY recorded_at_ms DESC, failure_key DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new ConsumerEventRow(
                        "PROTOCOL_FAILURE",
                        rs.getString("event_id"),
                        rs.getString("failure_key"),
                        null,
                        null,
                        null,
                        null,
                        "DEAD",
                        rs.getString("error_code"),
                        0,
                        0,
                        null,
                        null,
                        rs.getString("error_message"),
                        null,
                        rs.getString("stream_record_id"),
                        rs.getLong("recorded_at_ms")
                ),
                limit,
                offset
        );
        return new EventPageResult(rows, total);
    }

    private EventPageResult queryIndexTasksOnly(long activeGeneration, String taskStatus, int limit, int offset) {
        long total = queryCount(
                "SELECT COUNT(*) FROM search_index_task WHERE index_generation = ? AND status = ?",
                activeGeneration,
                taskStatus
        );
        List<ConsumerEventRow> rows = jdbcTemplate.query(
                """
                SELECT index_generation, article_id, target_projection_revision, operation,
                       status, attempts, max_attempts, due_at_ms, lease_until_ms,
                       last_error, cache_invalidation_status, updated_at_ms
                FROM search_index_task
                WHERE index_generation = ? AND status = ?
                ORDER BY updated_at_ms DESC, article_id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> mapTaskRow(rs),
                activeGeneration,
                taskStatus,
                limit,
                offset
        );
        return new EventPageResult(enrichTaskRowsWithLatestEventId(rows), total);
    }

    private EventPageResult queryCombinedTasksAndFailures(
            long activeGeneration, String statusFilter, int limit, int offset) {
        String taskWhere;
        if ("DEAD".equals(statusFilter)) {
            taskWhere = "index_generation = ? AND status = 'DEAD'";
        } else if ("FAILED".equals(statusFilter)) {
            taskWhere = "index_generation = ? AND (status = 'DEAD' OR (status = 'PENDING' AND (attempts > 0 OR last_error IS NOT NULL)))";
        } else {
            taskWhere = "index_generation = ?";
        }

        long taskCount = queryCount("SELECT COUNT(*) FROM search_index_task WHERE " + taskWhere, activeGeneration);
        long failCount = queryCount("SELECT COUNT(*) FROM search_event_failure");
        long total = taskCount + failCount;
        if (total == 0L || offset >= total) {
            return new EventPageResult(List.of(), total);
        }

        String unionSql = """
                SELECT record_type, event_id, failure_key, index_generation, article_id,
                       target_projection_revision, operation, normalized_status, raw_status,
                       attempts, max_attempts, due_at_ms, lease_until_ms,
                       last_error, cache_invalidation_status, stream_record_id, updated_at_ms
                FROM (
                    SELECT 'INDEX_TASK' AS record_type,
                           NULL AS event_id,
                           NULL AS failure_key,
                           index_generation,
                           article_id,
                           target_projection_revision,
                           operation,
                           CASE
                               WHEN status = 'LEASED' THEN 'PROCESSING'
                               WHEN status = 'DONE' THEN 'SUCCEEDED'
                               ELSE status
                           END AS normalized_status,
                           status AS raw_status,
                           attempts,
                           max_attempts,
                           due_at_ms,
                           lease_until_ms,
                           last_error,
                           cache_invalidation_status,
                           NULL AS stream_record_id,
                           updated_at_ms
                    FROM search_index_task
                    WHERE %s
                    UNION ALL
                    SELECT 'PROTOCOL_FAILURE' AS record_type,
                           event_id,
                           failure_key,
                           NULL AS index_generation,
                           NULL AS article_id,
                           NULL AS target_projection_revision,
                           NULL AS operation,
                           'DEAD' AS normalized_status,
                           error_code AS raw_status,
                           0 AS attempts,
                           0 AS max_attempts,
                           NULL AS due_at_ms,
                           NULL AS lease_until_ms,
                           error_message AS last_error,
                           NULL AS cache_invalidation_status,
                           stream_record_id,
                           recorded_at_ms AS updated_at_ms
                    FROM search_event_failure
                ) combined
                ORDER BY updated_at_ms DESC, record_type ASC, article_id DESC, failure_key DESC
                LIMIT ? OFFSET ?
                """.formatted(taskWhere);

        List<ConsumerEventRow> rows = jdbcTemplate.query(
                unionSql,
                (rs, rowNum) -> new ConsumerEventRow(
                        rs.getString("record_type"),
                        rs.getString("event_id"),
                        rs.getString("failure_key"),
                        nullableLong(rs, "index_generation"),
                        nullableLong(rs, "article_id"),
                        nullableLong(rs, "target_projection_revision"),
                        rs.getString("operation"),
                        rs.getString("normalized_status"),
                        rs.getString("raw_status"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        nullableLong(rs, "due_at_ms"),
                        nullableLong(rs, "lease_until_ms"),
                        rs.getString("last_error"),
                        rs.getString("cache_invalidation_status"),
                        rs.getString("stream_record_id"),
                        rs.getLong("updated_at_ms")
                ),
                activeGeneration,
                limit,
                offset
        );
        return new EventPageResult(enrichTaskRowsWithLatestEventId(rows), total);
    }

    private List<ConsumerEventRow> enrichTaskRowsWithLatestEventId(List<ConsumerEventRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<ConsumerEventRow> enriched = new ArrayList<>(rows.size());
        for (ConsumerEventRow row : rows) {
            if ("INDEX_TASK".equals(row.recordType()) && row.articleId() != null) {
                String latestEventId = findLatestAppliedEventIdForArticle(row.articleId());
                enriched.add(new ConsumerEventRow(
                        row.recordType(),
                        latestEventId,
                        row.failureKey(),
                        row.indexGeneration(),
                        row.articleId(),
                        row.targetProjectionRevision(),
                        row.operation(),
                        row.status(),
                        row.rawStatus(),
                        row.retryCount(),
                        row.maxAttempts(),
                        row.dueAtMs(),
                        row.leaseUntilMs(),
                        row.lastError(),
                        row.cacheInvalidationStatus(),
                        row.streamRecordId(),
                        row.updatedAtMs()
                ));
            } else {
                enriched.add(row);
            }
        }
        return enriched;
    }

    private String findLatestAppliedEventIdForArticle(long articleId) {
        List<String> ids = jdbcTemplate.query(
                """
                SELECT event_id
                FROM search_event_inbox
                WHERE aggregate_id = ?
                  AND status = 'APPLIED'
                ORDER BY processed_at_ms DESC, event_id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString("event_id"),
                Long.toString(articleId)
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private ConsumerEventRow mapTaskRow(ResultSet rs) throws SQLException {
        String raw = rs.getString("status");
        String normalized = switch (raw) {
            case "LEASED" -> "PROCESSING";
            case "DONE" -> "SUCCEEDED";
            default -> raw;
        };
        return new ConsumerEventRow(
                "INDEX_TASK",
                null,
                null,
                rs.getLong("index_generation"),
                rs.getLong("article_id"),
                rs.getLong("target_projection_revision"),
                rs.getString("operation"),
                normalized,
                raw,
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getLong("due_at_ms"),
                nullableLong(rs, "lease_until_ms"),
                rs.getString("last_error"),
                rs.getString("cache_invalidation_status"),
                null,
                rs.getLong("updated_at_ms")
        );
    }

    public Optional<ReplayAuditRecord> findReplayAuditForUpdate(String replayRequestId) {
        List<ReplayAuditRecord> rows = jdbcTemplate.query(
                """
                SELECT replay_request_id, operation_type, target_event_id, request_fingerprint,
                       caller_service, operator_id, response_status, requeued_count, response_json, created_at_ms
                FROM search_ops_replay_audit
                WHERE replay_request_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new ReplayAuditRecord(
                        rs.getString("replay_request_id"),
                        rs.getString("operation_type"),
                        rs.getString("target_event_id"),
                        rs.getString("request_fingerprint"),
                        rs.getString("caller_service"),
                        rs.getString("operator_id"),
                        rs.getString("response_status"),
                        rs.getLong("requeued_count"),
                        rs.getString("response_json"),
                        rs.getLong("created_at_ms")
                ),
                replayRequestId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean insertReplayAudit(ReplayAuditRecord record) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO search_ops_replay_audit (
                        replay_request_id, operation_type, target_event_id, request_fingerprint,
                        caller_service, operator_id, response_status, requeued_count, response_json, created_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.replayRequestId(),
                    record.operationType(),
                    record.targetEventId(),
                    record.requestFingerprint(),
                    record.callerService(),
                    record.operatorId(),
                    record.responseStatus(),
                    record.requeuedCount(),
                    record.responseJson(),
                    record.createdAtMs()
            );
            return true;
        } catch (DuplicateKeyException dup) {
            return false;
        }
    }

    public boolean hasProtocolFailureForEvent(String eventId) {
        return queryCount("SELECT COUNT(*) FROM search_event_failure WHERE event_id = ?", eventId) > 0L;
    }

    public Optional<InboxLookupRecord> findInboxByEventId(String eventId) {
        List<InboxLookupRecord> rows = jdbcTemplate.query(
                """
                SELECT event_id, aggregate_type, aggregate_id, source_revision, status
                FROM search_event_inbox
                WHERE event_id = ?
                """,
                (rs, rowNum) -> new InboxLookupRecord(
                        rs.getString("event_id"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getLong("source_revision"),
                        rs.getString("status")
                ),
                eventId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public BatchRetryOutcome requeueBatchInCurrentTx(
            String aliasName, String defaultPhysicalIndex, int limit, long nowMs) {
        IndexGenerationState gen = consumerRepository.ensureAndLockGeneration(aliasName, defaultPhysicalIndex, nowMs);
        if (!gen.isNormal()) {
            throw new InternalOpsException(409, "STATE_CONFLICT",
                    "Alias cutover is in progress (" + gen.cutoverState() + "); retry deferred", true);
        }
        long activeGen = gen.activeGeneration();
        long skippedActiveLease = queryCount(
                """
                SELECT COUNT(*)
                FROM search_index_task
                WHERE index_generation = ?
                  AND status = 'LEASED'
                  AND lease_until_ms IS NOT NULL
                  AND lease_until_ms > ?
                """,
                activeGen,
                nowMs
        );

        List<Long> candidateIds = jdbcTemplate.query(
                """
                SELECT article_id
                FROM search_index_task
                WHERE index_generation = ?
                  AND (
                      status = 'DEAD'
                      OR (status = 'PENDING' AND due_at_ms > ?)
                      OR (status = 'LEASED' AND lease_until_ms IS NOT NULL AND lease_until_ms <= ?)
                  )
                ORDER BY updated_at_ms ASC, article_id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getLong("article_id"),
                activeGen,
                nowMs,
                nowMs,
                Math.max(1, limit)
        );

        long requeued = 0L;
        for (Long articleId : candidateIds) {
            Optional<SearchIndexTaskRecord> maybeLocked = lockIndexTaskForUpdate(activeGen, articleId);
            if (maybeLocked.isEmpty()) {
                continue;
            }
            SearchIndexTaskRecord task = maybeLocked.get();
            if (task.status() == SearchIndexTaskStatus.LEASED
                    && task.leaseUntilMs() != null
                    && task.leaseUntilMs() > nowMs) {
                continue;
            }
            boolean eligible = task.status() == SearchIndexTaskStatus.DEAD
                    || (task.status() == SearchIndexTaskStatus.LEASED && task.leaseUntilMs() != null && task.leaseUntilMs() <= nowMs)
                    || (task.status() == SearchIndexTaskStatus.PENDING && task.dueAtMs() > nowMs);
            if (!eligible) {
                continue;
            }
            int nextAttempts = (task.status() == SearchIndexTaskStatus.DEAD || task.attempts() >= task.maxAttempts())
                    ? 0
                    : task.attempts();
            int updated = jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET status = 'PENDING',
                        attempts = ?,
                        due_at_ms = ?,
                        lease_until_ms = NULL,
                        claim_generation = claim_generation + 1,
                        updated_at_ms = ?
                    WHERE index_generation = ? AND article_id = ?
                    """,
                    nextAttempts,
                    nowMs,
                    nowMs,
                    activeGen,
                    articleId
            );
            if (updated == 1) {
                requeued++;
            }
        }

        // Expedite pending cache compensations (bounded)
        int cacheExpedited = 0;
        List<Long> cacheArticleIds = jdbcTemplate.query(
                """
                SELECT article_id
                FROM search_index_task
                WHERE index_generation = ?
                  AND cache_invalidation_status = 'PENDING'
                  AND (cache_invalidation_due_at_ms IS NULL OR cache_invalidation_due_at_ms > ?)
                ORDER BY updated_at_ms ASC, article_id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getLong("article_id"),
                activeGen,
                nowMs,
                Math.max(1, limit)
        );
        for (Long articleId : cacheArticleIds) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET cache_invalidation_due_at_ms = ?, updated_at_ms = ?
                    WHERE index_generation = ? AND article_id = ? AND cache_invalidation_status = 'PENDING'
                    """,
                    nowMs,
                    nowMs,
                    activeGen,
                    articleId
            );
            cacheExpedited += updated;
        }
        if (gen.activeCacheInvalidationStatus() == CacheInvalidationStatus.PENDING
                && (gen.activeCacheInvalidationDueAtMs() == null || gen.activeCacheInvalidationDueAtMs() > nowMs)) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE search_index_generation
                    SET active_cache_invalidation_due_at_ms = ?, updated_at_ms = ?
                    WHERE alias_name = ? AND active_cache_invalidation_status = 'PENDING'
                    """,
                    nowMs,
                    nowMs,
                    aliasName
            );
            cacheExpedited += updated;
        }
        return new BatchRetryOutcome(requeued, skippedActiveLease, cacheExpedited);
    }

    public SingleRetryOutcome requeueSingleArticleInCurrentTx(
            String aliasName, String defaultPhysicalIndex, long articleId, long nowMs) {
        IndexGenerationState gen = consumerRepository.ensureAndLockGeneration(aliasName, defaultPhysicalIndex, nowMs);
        if (!gen.isNormal()) {
            throw new InternalOpsException(409, "STATE_CONFLICT",
                    "Alias cutover is in progress (" + gen.cutoverState() + "); retry deferred", true);
        }
        long activeGen = gen.activeGeneration();
        Optional<SearchIndexTaskRecord> maybeLocked = lockIndexTaskForUpdate(activeGen, articleId);
        if (maybeLocked.isEmpty()) {
            return new SingleRetryOutcome("NOOP", 0L, "NO_ACTIVE_INDEX_TASK");
        }
        SearchIndexTaskRecord task = maybeLocked.get();
        if (task.status() == SearchIndexTaskStatus.LEASED) {
            if (task.leaseUntilMs() != null && task.leaseUntilMs() > nowMs) {
                throw new InternalOpsException(409, "STATE_CONFLICT",
                        "Index task for article " + articleId + " holds an active worker lease", true);
            }
            int nextAttempts = task.attempts() >= task.maxAttempts() ? 0 : task.attempts();
            jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET status = 'PENDING',
                        attempts = ?,
                        due_at_ms = ?,
                        lease_until_ms = NULL,
                        claim_generation = claim_generation + 1,
                        updated_at_ms = ?
                    WHERE index_generation = ? AND article_id = ?
                    """,
                    nextAttempts,
                    nowMs,
                    nowMs,
                    activeGen,
                    articleId
            );
            return new SingleRetryOutcome("ACCEPTED", 1L, "EXPIRED_LEASE_REQUEUED");
        }
        if (task.status() == SearchIndexTaskStatus.DONE) {
            if (task.cacheInvalidationStatus() == CacheInvalidationStatus.PENDING) {
                jdbcTemplate.update(
                        """
                        UPDATE search_index_task
                        SET cache_invalidation_due_at_ms = ?, updated_at_ms = ?
                        WHERE index_generation = ? AND article_id = ? AND cache_invalidation_status = 'PENDING'
                        """,
                        nowMs,
                        nowMs,
                        activeGen,
                        articleId
                );
                return new SingleRetryOutcome("ACCEPTED", 1L, "CACHE_COMPENSATION_EXPEDITED");
            }
            return new SingleRetryOutcome("NOOP", 0L, "ALREADY_APPLIED");
        }
        if (task.status() == SearchIndexTaskStatus.DEAD) {
            jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET status = 'PENDING',
                        attempts = 0,
                        due_at_ms = ?,
                        lease_until_ms = NULL,
                        claim_generation = claim_generation + 1,
                        updated_at_ms = ?
                    WHERE index_generation = ? AND article_id = ?
                    """,
                    nowMs,
                    nowMs,
                    activeGen,
                    articleId
            );
            return new SingleRetryOutcome("ACCEPTED", 1L, "DEAD_TASK_REQUEUED");
        }
        // PENDING
        if (task.attempts() > 0 || task.lastError() != null || task.dueAtMs() > nowMs) {
            int nextAttempts = task.attempts() >= task.maxAttempts() ? 0 : task.attempts();
            jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET attempts = ?,
                        due_at_ms = ?,
                        lease_until_ms = NULL,
                        claim_generation = claim_generation + 1,
                        updated_at_ms = ?
                    WHERE index_generation = ? AND article_id = ?
                    """,
                    nextAttempts,
                    nowMs,
                    nowMs,
                    activeGen,
                    articleId
            );
            return new SingleRetryOutcome("ACCEPTED", 1L, "PENDING_BACKOFF_EXPEDITED");
        }
        return new SingleRetryOutcome("NOOP", 0L, "ALREADY_QUEUED");
    }

    private Optional<SearchIndexTaskRecord> lockIndexTaskForUpdate(long indexGeneration, long articleId) {
        List<SearchIndexTaskRecord> rows = jdbcTemplate.query(
                """
                SELECT index_generation, article_id, target_index, target_projection_revision,
                       operation, payload_json, status, attempts, max_attempts, due_at_ms,
                       lease_until_ms, claim_generation, last_error, es_applied_revision,
                       cache_invalidation_status, cache_invalidation_attempts,
                       cache_invalidation_due_at_ms, cache_last_error, updated_at_ms
                FROM search_index_task
                WHERE index_generation = ? AND article_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new SearchIndexTaskRecord(
                        rs.getLong("index_generation"),
                        rs.getLong("article_id"),
                        rs.getString("target_index"),
                        rs.getLong("target_projection_revision"),
                        com.knode.search.consumer.persistence.SearchIndexOperation.valueOf(rs.getString("operation")),
                        rs.getString("payload_json"),
                        SearchIndexTaskStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        rs.getLong("due_at_ms"),
                        nullableLong(rs, "lease_until_ms"),
                        rs.getLong("claim_generation"),
                        rs.getString("last_error"),
                        rs.getLong("es_applied_revision"),
                        CacheInvalidationStatus.valueOf(rs.getString("cache_invalidation_status")),
                        rs.getInt("cache_invalidation_attempts"),
                        nullableLong(rs, "cache_invalidation_due_at_ms"),
                        rs.getString("cache_last_error"),
                        rs.getLong("updated_at_ms")
                ),
                indexGeneration,
                articleId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private ActiveRebuildSummary loadActiveRebuild(String aliasName) {
        List<ActiveRebuildSummary> rows = jdbcTemplate.query(
                """
                SELECT job_id, phase, candidate_generation, candidate_physical_index,
                       snapshot_items_imported, incremental_events_applied, updated_at_ms
                FROM search_rebuild_job
                WHERE active_lock_key = ?
                """,
                (rs, rowNum) -> new ActiveRebuildSummary(
                        rs.getString("job_id"),
                        rs.getString("phase"),
                        rs.getLong("candidate_generation"),
                        rs.getString("candidate_physical_index"),
                        rs.getLong("snapshot_items_imported"),
                        rs.getLong("incremental_events_applied"),
                        rs.getLong("updated_at_ms")
                ),
                aliasName
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CompletedRebuildSummary loadLastCompletedRebuild(String aliasName) {
        List<CompletedRebuildSummary> rows = jdbcTemplate.query(
                """
                SELECT job_id, candidate_generation, updated_at_ms
                FROM search_rebuild_job
                WHERE alias_name = ? AND phase = 'COMPLETED'
                ORDER BY updated_at_ms DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new CompletedRebuildSummary(
                        rs.getString("job_id"),
                        rs.getLong("candidate_generation"),
                        rs.getLong("updated_at_ms")
                ),
                aliasName
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long queryCount(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record ActiveRebuildSummary(
            String jobId,
            String phase,
            long candidateGeneration,
            String candidatePhysicalIndex,
            long snapshotItemsImported,
            long incrementalEventsApplied,
            long updatedAtMs
    ) {
    }

    public record CompletedRebuildSummary(
            String jobId,
            long candidateGeneration,
            long completedAtMs
    ) {
    }

    public record OpsSummaryDbSnapshot(
            String aliasName,
            long activeGeneration,
            String activePhysicalIndex,
            String cutoverState,
            Map<String, Long> inboxCounts,
            Map<String, Long> taskCounts,
            long expiredLeased,
            long taskCachePending,
            String generationCacheStatus,
            int generationCacheAttempts,
            ActiveRebuildSummary activeRebuild,
            CompletedRebuildSummary lastCompletedRebuild
    ) {
    }

    public record ConsumerEventRow(
            String recordType,
            String eventId,
            String failureKey,
            Long indexGeneration,
            Long articleId,
            Long targetProjectionRevision,
            String operation,
            String status,
            String rawStatus,
            int retryCount,
            int maxAttempts,
            Long dueAtMs,
            Long leaseUntilMs,
            String lastError,
            String cacheInvalidationStatus,
            String streamRecordId,
            long updatedAtMs
    ) {
    }

    public record EventPageResult(List<ConsumerEventRow> rows, long total) {
    }

    public record ReplayAuditRecord(
            String replayRequestId,
            String operationType,
            String targetEventId,
            String requestFingerprint,
            String callerService,
            String operatorId,
            String responseStatus,
            long requeuedCount,
            String responseJson,
            long createdAtMs
    ) {
    }

    public record InboxLookupRecord(
            String eventId,
            String aggregateType,
            String aggregateId,
            long sourceRevision,
            String status
    ) {
    }

    public record BatchRetryOutcome(
            long requeued,
            long skippedActiveLease,
            long cacheCompensationsExpedited
    ) {
    }

    public record SingleRetryOutcome(
            String status,
            long requeued,
            String reason
    ) {
    }
}
