package com.knode.search.consumer.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.event.ParsedContentEvent;
import com.knode.search.consumer.event.ProtocolFailure;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
public class SearchConsumerRepository {
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SearchConsumerRepository(
            @Qualifier("searchConsumerJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean insertInboxIfAbsent(ParsedContentEvent event, String streamRecordId, String status, long nowMs) {
        int rows = jdbcTemplate.update(
                """
                INSERT IGNORE INTO search_event_inbox (
                    event_id, stream_record_id, event_type, schema_version,
                    aggregate_type, aggregate_id, source_revision, visibility,
                    status, received_at_ms, processed_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.eventId(),
                streamRecordId,
                event.eventType(),
                event.schemaVersion(),
                event.aggregateType(),
                event.aggregateId(),
                event.sourceRevision(),
                event.visibilityRaw(),
                status,
                nowMs,
                nowMs
        );
        return rows == 1;
    }

    public void updateInboxStatus(String eventId, String status, long nowMs) {
        jdbcTemplate.update(
                "UPDATE search_event_inbox SET status = ?, processed_at_ms = ? WHERE event_id = ?",
                status, nowMs, eventId
        );
    }

    public Optional<String> findInboxStatus(String eventId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT status FROM search_event_inbox WHERE event_id = ?",
                (rs, rowNum) -> rs.getString("status"),
                eventId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean insertFailureIfAbsent(ProtocolFailure failure, String streamRecordId, long nowMs) {
        String key = failure.failureKey(streamRecordId);
        int rows = jdbcTemplate.update(
                """
                INSERT IGNORE INTO search_event_failure (
                    failure_key, event_id, stream_record_id, error_code,
                    error_message, payload_sha256, payload_bytes, recorded_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                key,
                failure.eventId(),
                streamRecordId,
                failure.errorCode(),
                boundedError(failure.errorMessage()),
                failure.payloadSha256(),
                failure.payloadBytes(),
                nowMs
        );
        return rows == 1;
    }

    public ArticleProjectionRecord ensureAndLockProjection(long articleId, long nowMs) {
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO search_article_projection (
                    article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, updated_at_ms
                ) VALUES (?, 0, 0, 'NONE', 0, 0, 0, ?)
                """,
                articleId,
                nowMs
        );
        return jdbcTemplate.queryForObject(
                """
                SELECT article_id, has_content, content_revision, visibility,
                       counter_revision, view_count, projection_revision,
                       title, summary, content, category_id, category_name,
                       tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                FROM search_article_projection
                WHERE article_id = ?
                FOR UPDATE
                """,
                projectionRowMapper(),
                articleId
        );
    }

    public Optional<ArticleProjectionRecord> findProjection(long articleId) {
        List<ArticleProjectionRecord> rows = jdbcTemplate.query(
                """
                SELECT article_id, has_content, content_revision, visibility,
                       counter_revision, view_count, projection_revision,
                       title, summary, content, category_id, category_name,
                       tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                FROM search_article_projection
                WHERE article_id = ?
                """,
                projectionRowMapper(),
                articleId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void updateProjection(ArticleProjectionRecord record) {
        String tagNamesJson = serializeTagNames(record.tagNames());
        jdbcTemplate.update(
                """
                UPDATE search_article_projection
                SET has_content = ?,
                    content_revision = ?,
                    visibility = ?,
                    counter_revision = ?,
                    view_count = ?,
                    projection_revision = ?,
                    title = ?,
                    summary = ?,
                    content = ?,
                    category_id = ?,
                    category_name = ?,
                    tag_names_json = ?,
                    thumbnail = ?,
                    is_top = ?,
                    create_time_ms = ?,
                    updated_at_ms = ?
                WHERE article_id = ?
                """,
                new Object[]{
                        record.hasContent() ? 1 : 0,
                        record.contentRevision(),
                        record.visibility().name(),
                        record.counterRevision(),
                        record.viewCount(),
                        record.projectionRevision(),
                        record.title(),
                        record.summary(),
                        record.content(),
                        record.categoryId(),
                        record.categoryName(),
                        tagNamesJson,
                        record.thumbnail(),
                        record.isTop(),
                        record.createTimeMs(),
                        record.updatedAtMs(),
                        record.articleId()
                },
                new int[]{
                        Types.TINYINT,
                        Types.BIGINT,
                        Types.VARCHAR,
                        Types.BIGINT,
                        Types.BIGINT,
                        Types.BIGINT,
                        Types.VARCHAR,
                        Types.VARCHAR,
                        Types.LONGVARCHAR,
                        Types.BIGINT,
                        Types.VARCHAR,
                        Types.LONGVARCHAR,
                        Types.VARCHAR,
                        Types.VARCHAR,
                        Types.BIGINT,
                        Types.BIGINT,
                        Types.BIGINT
                }
        );
    }

    public void upsertPendingIndexTask(
            long articleId,
            long targetProjectionRevision,
            SearchIndexOperation operation,
            String payloadJson,
            int maxAttempts,
            long nowMs) {
        List<Long> existing = jdbcTemplate.query(
                "SELECT article_id FROM search_index_task WHERE article_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("article_id"),
                articleId
        );
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO search_index_task (
                        article_id, target_projection_revision, operation, payload_json,
                        status, attempts, max_attempts, due_at_ms, lease_until_ms,
                        claim_generation, last_error, es_applied_revision,
                        cache_invalidation_status, cache_invalidation_attempts,
                        cache_invalidation_due_at_ms, cache_last_error, updated_at_ms
                    ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, NULL, 0, NULL, 0, 'NONE', 0, NULL, NULL, ?)
                    """,
                    articleId,
                    targetProjectionRevision,
                    operation.name(),
                    payloadJson,
                    maxAttempts,
                    nowMs,
                    nowMs
            );
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET target_projection_revision = ?,
                        operation = ?,
                        payload_json = ?,
                        status = 'PENDING',
                        attempts = 0,
                        max_attempts = ?,
                        due_at_ms = ?,
                        lease_until_ms = NULL,
                        last_error = NULL,
                        updated_at_ms = ?
                    WHERE article_id = ?
                    """,
                    targetProjectionRevision,
                    operation.name(),
                    payloadJson,
                    maxAttempts,
                    nowMs,
                    nowMs,
                    articleId
            );
        }
    }

    public Optional<SearchIndexTaskRecord> findIndexTask(long articleId) {
        List<SearchIndexTaskRecord> rows = jdbcTemplate.query(
                """
                SELECT article_id, target_projection_revision, operation, payload_json,
                       status, attempts, max_attempts, due_at_ms, lease_until_ms,
                       claim_generation, last_error, es_applied_revision,
                       cache_invalidation_status, cache_invalidation_attempts,
                       cache_invalidation_due_at_ms, cache_last_error, updated_at_ms
                FROM search_index_task
                WHERE article_id = ?
                """,
                indexTaskRowMapper(),
                articleId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<SearchIndexTaskRecord> claimDueTasksInCurrentTx(int batchSize, long leaseDurationMs, long nowMs) {
        List<Long> candidateIds = jdbcTemplate.query(
                """
                SELECT article_id
                FROM search_index_task
                WHERE (status = 'PENDING' AND due_at_ms <= ?)
                   OR (status = 'LEASED' AND lease_until_ms <= ?)
                ORDER BY due_at_ms ASC, article_id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getLong("article_id"),
                nowMs,
                nowMs,
                Math.max(1, batchSize)
        );
        List<SearchIndexTaskRecord> claimed = new ArrayList<>();
        for (Long articleId : candidateIds) {
            List<SearchIndexTaskRecord> locked = jdbcTemplate.query(
                    """
                    SELECT article_id, target_projection_revision, operation, payload_json,
                           status, attempts, max_attempts, due_at_ms, lease_until_ms,
                           claim_generation, last_error, es_applied_revision,
                           cache_invalidation_status, cache_invalidation_attempts,
                           cache_invalidation_due_at_ms, cache_last_error, updated_at_ms
                    FROM search_index_task
                    WHERE article_id = ?
                    FOR UPDATE
                    """,
                    indexTaskRowMapper(),
                    articleId
            );
            if (locked.isEmpty()) {
                continue;
            }
            SearchIndexTaskRecord current = locked.get(0);
            boolean eligiblePending = current.status() == SearchIndexTaskStatus.PENDING
                    && current.dueAtMs() <= nowMs;
            boolean eligibleExpiredLease = current.status() == SearchIndexTaskStatus.LEASED
                    && current.leaseUntilMs() != null
                    && current.leaseUntilMs() <= nowMs;
            if (!eligiblePending && !eligibleExpiredLease) {
                continue;
            }

            int nextAttempts = eligibleExpiredLease ? current.attempts() + 1 : current.attempts();
            if (eligibleExpiredLease && nextAttempts >= current.maxAttempts()) {
                jdbcTemplate.update(
                        """
                        UPDATE search_index_task
                        SET status = 'DEAD',
                            attempts = ?,
                            lease_until_ms = NULL,
                            last_error = 'LEASE_EXPIRED_MAX_ATTEMPTS',
                            updated_at_ms = ?
                        WHERE article_id = ?
                        """,
                        nextAttempts,
                        nowMs,
                        articleId
                );
                continue;
            }

            long nextGeneration = current.claimGeneration() + 1L;
            long leaseUntilMs = nowMs + Math.max(1_000L, leaseDurationMs);
            jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET status = 'LEASED',
                        attempts = ?,
                        lease_until_ms = ?,
                        claim_generation = ?,
                        updated_at_ms = ?
                    WHERE article_id = ?
                    """,
                    nextAttempts,
                    leaseUntilMs,
                    nextGeneration,
                    nowMs,
                    articleId
            );
            claimed.add(new SearchIndexTaskRecord(
                    current.articleId(),
                    current.targetProjectionRevision(),
                    current.operation(),
                    current.payloadJson(),
                    SearchIndexTaskStatus.LEASED,
                    nextAttempts,
                    current.maxAttempts(),
                    current.dueAtMs(),
                    leaseUntilMs,
                    nextGeneration,
                    current.lastError(),
                    current.esAppliedRevision(),
                    current.cacheInvalidationStatus(),
                    current.cacheInvalidationAttempts(),
                    current.cacheInvalidationDueAtMs(),
                    current.cacheLastError(),
                    nowMs
            ));
        }
        return claimed;
    }

    public boolean markEsAppliedConditionally(
            long articleId,
            long targetProjectionRevision,
            long claimGeneration,
            boolean cacheInvalidationNeeded,
            long nowMs) {
        String cacheStatus = cacheInvalidationNeeded
                ? CacheInvalidationStatus.PENDING.name()
                : CacheInvalidationStatus.DONE.name();
        Long cacheDueAt = cacheInvalidationNeeded ? nowMs : null;
        int updated = jdbcTemplate.update(
                """
                UPDATE search_index_task
                SET status = 'DONE',
                    lease_until_ms = NULL,
                    last_error = NULL,
                    es_applied_revision = CASE
                        WHEN es_applied_revision < ? THEN ?
                        ELSE es_applied_revision
                    END,
                    cache_invalidation_status = ?,
                    cache_invalidation_attempts = 0,
                    cache_invalidation_due_at_ms = ?,
                    cache_last_error = NULL,
                    updated_at_ms = ?
                WHERE article_id = ?
                  AND target_projection_revision = ?
                  AND claim_generation = ?
                  AND status = 'LEASED'
                """,
                targetProjectionRevision,
                targetProjectionRevision,
                cacheStatus,
                cacheDueAt,
                nowMs,
                articleId,
                targetProjectionRevision,
                claimGeneration
        );
        return updated == 1;
    }

    public boolean markSupersededConditionally(
            long articleId,
            long targetProjectionRevision,
            long claimGeneration,
            long nowMs) {
        int updated = jdbcTemplate.update(
                """
                UPDATE search_index_task
                SET status = 'DONE',
                    lease_until_ms = NULL,
                    last_error = NULL,
                    updated_at_ms = ?
                WHERE article_id = ?
                  AND target_projection_revision = ?
                  AND claim_generation = ?
                  AND status = 'LEASED'
                """,
                nowMs,
                articleId,
                targetProjectionRevision,
                claimGeneration
        );
        return updated == 1;
    }

    public boolean markRetryOrDeadConditionallyInCurrentTx(
            long articleId,
            long targetProjectionRevision,
            long claimGeneration,
            String errorMessage,
            long baseRetryDelayMs,
            long maxRetryDelayMs,
            long nowMs) {
        List<SearchIndexTaskRecord> locked = jdbcTemplate.query(
                """
                SELECT article_id, target_projection_revision, operation, payload_json,
                       status, attempts, max_attempts, due_at_ms, lease_until_ms,
                       claim_generation, last_error, es_applied_revision,
                       cache_invalidation_status, cache_invalidation_attempts,
                       cache_invalidation_due_at_ms, cache_last_error, updated_at_ms
                FROM search_index_task
                WHERE article_id = ?
                FOR UPDATE
                """,
                indexTaskRowMapper(),
                articleId
        );
        if (locked.isEmpty()) {
            return false;
        }
        SearchIndexTaskRecord current = locked.get(0);
        if (current.status() != SearchIndexTaskStatus.LEASED
                || current.targetProjectionRevision() != targetProjectionRevision
                || current.claimGeneration() != claimGeneration) {
            return false;
        }
        int nextAttempts = current.attempts() + 1;
        String safeError = boundedError(errorMessage);
        if (nextAttempts >= current.maxAttempts()) {
            jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET status = 'DEAD',
                        attempts = ?,
                        lease_until_ms = NULL,
                        last_error = ?,
                        updated_at_ms = ?
                    WHERE article_id = ?
                      AND target_projection_revision = ?
                      AND claim_generation = ?
                      AND status = 'LEASED'
                    """,
                    nextAttempts,
                    safeError,
                    nowMs,
                    articleId,
                    targetProjectionRevision,
                    claimGeneration
            );
            return true;
        }
        long delayMs = computeBackoffMs(nextAttempts, baseRetryDelayMs, maxRetryDelayMs);
        jdbcTemplate.update(
                """
                UPDATE search_index_task
                SET status = 'PENDING',
                    attempts = ?,
                    due_at_ms = ?,
                    lease_until_ms = NULL,
                    last_error = ?,
                    updated_at_ms = ?
                WHERE article_id = ?
                  AND target_projection_revision = ?
                  AND claim_generation = ?
                  AND status = 'LEASED'
                """,
                nextAttempts,
                nowMs + delayMs,
                safeError,
                nowMs,
                articleId,
                targetProjectionRevision,
                claimGeneration
        );
        return true;
    }

    public boolean markCacheInvalidationDoneConditionally(
            long articleId,
            long expectedEsAppliedRevision,
            long nowMs) {
        int updated = jdbcTemplate.update(
                """
                UPDATE search_index_task
                SET cache_invalidation_status = 'DONE',
                    cache_invalidation_due_at_ms = NULL,
                    cache_last_error = NULL,
                    updated_at_ms = ?
                WHERE article_id = ?
                  AND es_applied_revision = ?
                  AND cache_invalidation_status = 'PENDING'
                """,
                nowMs,
                articleId,
                expectedEsAppliedRevision
        );
        return updated == 1;
    }

    public boolean markCacheInvalidationRetryConditionally(
            long articleId,
            long expectedEsAppliedRevision,
            int currentAttempts,
            String errorMessage,
            long baseDelayMs,
            long maxDelayMs,
            long nowMs) {
        int nextAttempts = currentAttempts + 1;
        long delayMs = computeBackoffMs(nextAttempts, baseDelayMs, maxDelayMs);
        int updated = jdbcTemplate.update(
                """
                UPDATE search_index_task
                SET cache_invalidation_attempts = ?,
                    cache_invalidation_due_at_ms = ?,
                    cache_last_error = ?,
                    updated_at_ms = ?
                WHERE article_id = ?
                  AND es_applied_revision = ?
                  AND cache_invalidation_status = 'PENDING'
                """,
                nextAttempts,
                nowMs + delayMs,
                boundedError(errorMessage),
                nowMs,
                articleId,
                expectedEsAppliedRevision
        );
        return updated == 1;
    }

    public List<CacheCompensationCandidate> findDueCacheInvalidations(int batchSize, long nowMs) {
        return jdbcTemplate.query(
                """
                SELECT article_id, es_applied_revision, cache_invalidation_attempts
                FROM search_index_task
                WHERE cache_invalidation_status = 'PENDING'
                  AND cache_invalidation_due_at_ms IS NOT NULL
                  AND cache_invalidation_due_at_ms <= ?
                ORDER BY cache_invalidation_due_at_ms ASC, article_id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new CacheCompensationCandidate(
                        rs.getLong("article_id"),
                        rs.getLong("es_applied_revision"),
                        rs.getInt("cache_invalidation_attempts")
                ),
                nowMs,
                Math.max(1, batchSize)
        );
    }

    private RowMapper<ArticleProjectionRecord> projectionRowMapper() {
        return (rs, rowNum) -> new ArticleProjectionRecord(
                rs.getLong("article_id"),
                rs.getInt("has_content") != 0,
                rs.getLong("content_revision"),
                ArticleVisibility.valueOf(rs.getString("visibility")),
                rs.getLong("counter_revision"),
                rs.getLong("view_count"),
                rs.getLong("projection_revision"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("content"),
                nullableLong(rs, "category_id"),
                rs.getString("category_name"),
                deserializeTagNames(rs.getString("tag_names_json")),
                rs.getString("thumbnail"),
                rs.getString("is_top"),
                nullableLong(rs, "create_time_ms"),
                rs.getLong("updated_at_ms")
        );
    }

    private RowMapper<SearchIndexTaskRecord> indexTaskRowMapper() {
        return (rs, rowNum) -> new SearchIndexTaskRecord(
                rs.getLong("article_id"),
                rs.getLong("target_projection_revision"),
                SearchIndexOperation.valueOf(rs.getString("operation")),
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
        );
    }

    private String serializeTagNames(List<String> tagNames) {
        if (tagNames == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tagNames);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize tagNames", ex);
        }
    }

    private List<String> deserializeTagNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, STRING_LIST_TYPE);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static long computeBackoffMs(int attempt, long baseDelayMs, long maxDelayMs) {
        long safeBase = Math.max(100L, baseDelayMs);
        long safeMax = Math.max(safeBase, maxDelayMs);
        int shift = Math.min(Math.max(0, attempt - 1), 16);
        long delay = safeBase * (1L << shift);
        return Math.min(safeMax, delay);
    }

    private static String boundedError(String message) {
        if (message == null || message.isBlank()) {
            return "UNKNOWN_ERROR";
        }
        String sanitized = message.replace('\r', ' ').replace('\n', ' ').trim();
        return sanitized.length() > 500 ? sanitized.substring(0, 500) : sanitized;
    }

    public record CacheCompensationCandidate(long articleId, long esAppliedRevision, int attempts) {
    }
}
