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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
public class SearchConsumerRepository {
    public static final long DEFAULT_GENERATION = 1L;
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

    // === Active Index Generation & Cutover Gate ===

    public IndexGenerationState ensureAndLockGeneration(
            String aliasName, String defaultPhysicalIndex, long nowMs) {
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO search_index_generation (
                    alias_name, active_generation, active_physical_index,
                    require_cold_visibility_guard, cutover_state,
                    active_cache_invalidation_status, active_cache_invalidation_attempts,
                    updated_at_ms
                ) VALUES (?, 1, ?, 0, 'NORMAL', 'DONE', 0, ?)
                """,
                aliasName,
                defaultPhysicalIndex,
                nowMs
        );
        return jdbcTemplate.queryForObject(
                """
                SELECT alias_name, active_generation, active_physical_index,
                       require_cold_visibility_guard, cutover_state, cutover_job_id,
                       previous_generation, previous_physical_index,
                       target_generation, target_physical_index, cutover_started_at_ms,
                       active_cache_invalidation_status, active_cache_invalidation_attempts,
                       active_cache_invalidation_due_at_ms, active_cache_last_error,
                       active_cutover_bypass_owner, updated_at_ms
                FROM search_index_generation
                WHERE alias_name = ?
                FOR UPDATE
                """,
                generationRowMapper(),
                aliasName
        );
    }

    public Optional<IndexGenerationState> findGenerationState(String aliasName) {
        List<IndexGenerationState> rows = jdbcTemplate.query(
                """
                SELECT alias_name, active_generation, active_physical_index,
                       require_cold_visibility_guard, cutover_state, cutover_job_id,
                       previous_generation, previous_physical_index,
                       target_generation, target_physical_index, cutover_started_at_ms,
                       active_cache_invalidation_status, active_cache_invalidation_attempts,
                       active_cache_invalidation_due_at_ms, active_cache_last_error,
                       active_cutover_bypass_owner, updated_at_ms
                FROM search_index_generation
                WHERE alias_name = ?
                """,
                generationRowMapper(),
                aliasName
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean markGenerationCacheInvalidationDone(String aliasName, long expectedGeneration, long nowMs) {
        int updated = jdbcTemplate.update(
                """
                UPDATE search_index_generation
                SET active_cache_invalidation_status = 'DONE',
                    active_cache_invalidation_due_at_ms = NULL,
                    active_cache_last_error = NULL,
                    active_cutover_bypass_owner = NULL,
                    updated_at_ms = ?
                WHERE alias_name = ?
                  AND active_generation = ?
                  AND active_cache_invalidation_status = 'PENDING'
                """,
                nowMs,
                aliasName,
                expectedGeneration
        );
        return updated == 1;
    }

    public boolean markGenerationCacheInvalidationRetry(
            String aliasName,
            long expectedGeneration,
            int currentAttempts,
            String errorMessage,
            long baseDelayMs,
            long maxDelayMs,
            long nowMs) {
        int nextAttempts = currentAttempts + 1;
        long delayMs = computeBackoffMs(nextAttempts, baseDelayMs, maxDelayMs);
        int updated = jdbcTemplate.update(
                """
                UPDATE search_index_generation
                SET active_cache_invalidation_attempts = ?,
                    active_cache_invalidation_due_at_ms = ?,
                    active_cache_last_error = ?,
                    updated_at_ms = ?
                WHERE alias_name = ?
                  AND active_generation = ?
                  AND active_cache_invalidation_status = 'PENDING'
                """,
                nextAttempts,
                nowMs + delayMs,
                boundedError(errorMessage),
                nowMs,
                aliasName,
                expectedGeneration
        );
        return updated == 1;
    }

    public List<GenerationCacheCompensationCandidate> findDueGenerationCacheInvalidations(long nowMs) {
        return jdbcTemplate.query(
                """
                SELECT alias_name, active_generation, active_cache_invalidation_attempts, active_cutover_bypass_owner
                FROM search_index_generation
                WHERE active_cache_invalidation_status = 'PENDING'
                  AND active_cache_invalidation_due_at_ms IS NOT NULL
                  AND active_cache_invalidation_due_at_ms <= ?
                ORDER BY active_cache_invalidation_due_at_ms ASC
                """,
                (rs, rowNum) -> new GenerationCacheCompensationCandidate(
                        rs.getString("alias_name"),
                        rs.getLong("active_generation"),
                        rs.getInt("active_cache_invalidation_attempts"),
                        rs.getString("active_cutover_bypass_owner")
                ),
                nowMs
        );
    }

    // === Cross-Generation Article Version Barrier ===

    public Optional<ArticleVersionBarrier> findVersionBarrier(long articleId) {
        List<ArticleVersionBarrier> rows = jdbcTemplate.query(
                """
                SELECT article_id, max_content_revision, last_visibility, updated_at_ms
                FROM search_article_version_barrier
                WHERE article_id = ?
                """,
                (rs, rowNum) -> new ArticleVersionBarrier(
                        rs.getLong("article_id"),
                        rs.getLong("max_content_revision"),
                        ArticleVisibility.valueOf(rs.getString("last_visibility")),
                        rs.getLong("updated_at_ms")
                ),
                articleId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public ArticleVersionBarrier ensureAndLockVersionBarrier(long articleId, long nowMs) {
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO search_article_version_barrier (
                    article_id, max_content_revision, last_visibility, updated_at_ms
                ) VALUES (?, 0, 'NONE', ?)
                """,
                articleId,
                nowMs
        );
        return jdbcTemplate.queryForObject(
                """
                SELECT article_id, max_content_revision, last_visibility, updated_at_ms
                FROM search_article_version_barrier
                WHERE article_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new ArticleVersionBarrier(
                        rs.getLong("article_id"),
                        rs.getLong("max_content_revision"),
                        ArticleVisibility.valueOf(rs.getString("last_visibility")),
                        rs.getLong("updated_at_ms")
                ),
                articleId
        );
    }

    public static int visibilityRank(ArticleVisibility visibility) {
        if (visibility == null) {
            return 0;
        }
        return switch (visibility) {
            case DELETED -> 3;
            case WITHDRAWN -> 2;
            case PUBLIC -> 1;
            case NONE -> 0;
        };
    }

    public void advanceVersionBarrierIfHigher(
            long articleId, long contentRevision, ArticleVisibility visibility, long nowMs) {
        ArticleVersionBarrier current = ensureAndLockVersionBarrier(articleId, nowMs);
        if (contentRevision > current.maxContentRevision()
                || (contentRevision == current.maxContentRevision()
                && visibilityRank(visibility) > visibilityRank(current.lastVisibility()))) {
            jdbcTemplate.update(
                    """
                    UPDATE search_article_version_barrier
                    SET max_content_revision = ?,
                        last_visibility = ?,
                        updated_at_ms = ?
                    WHERE article_id = ?
                    """,
                    contentRevision,
                    visibility.name(),
                    nowMs,
                    articleId
            );
        }
    }

    /**
     * Atomically promotes all content/visibility versions from a newly activated candidate generation
     * into the cross-generation version barrier inside the cutover activation transaction.
     * Existing higher or stronger barriers (such as prior-generation DELETED/WITHDRAWN barriers)
     * are strictly preserved.
     */
    public void promoteGenerationVersionBarriersInCurrentTx(long indexGeneration, long nowMs) {
        jdbcTemplate.update(
                """
                UPDATE search_article_version_barrier
                SET last_visibility = (
                        SELECT p.visibility
                        FROM search_article_projection p
                        WHERE p.index_generation = ?
                          AND p.article_id = search_article_version_barrier.article_id
                    ),
                    max_content_revision = (
                        SELECT p.content_revision
                        FROM search_article_projection p
                        WHERE p.index_generation = ?
                          AND p.article_id = search_article_version_barrier.article_id
                    ),
                    updated_at_ms = ?
                WHERE EXISTS (
                    SELECT 1
                    FROM search_article_projection p
                    WHERE p.index_generation = ?
                      AND p.article_id = search_article_version_barrier.article_id
                      AND p.has_content = 1
                      AND p.content_revision > 0
                      AND (
                          p.content_revision > search_article_version_barrier.max_content_revision
                          OR (
                              p.content_revision = search_article_version_barrier.max_content_revision
                              AND (
                                  CASE p.visibility WHEN 'DELETED' THEN 3 WHEN 'WITHDRAWN' THEN 2 WHEN 'PUBLIC' THEN 1 ELSE 0 END
                                  > CASE search_article_version_barrier.last_visibility WHEN 'DELETED' THEN 3 WHEN 'WITHDRAWN' THEN 2 WHEN 'PUBLIC' THEN 1 ELSE 0 END
                              )
                          )
                      )
                )
                """,
                indexGeneration,
                indexGeneration,
                nowMs,
                indexGeneration
        );
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO search_article_version_barrier (
                    article_id, max_content_revision, last_visibility, updated_at_ms
                )
                SELECT p.article_id, p.content_revision, p.visibility, ?
                FROM search_article_projection p
                WHERE p.index_generation = ?
                  AND p.has_content = 1
                  AND p.content_revision > 0
                """,
                nowMs,
                indexGeneration
        );
    }

    /**
     * Resolves the effective cross-generation barrier for an active-generation read/write path.
     * If a legacy pre-R6 unactivated candidate generation polluted search_article_version_barrier
     * beyond all activated generations, restores the effective barrier strictly to the highest
     * activated-generation projection (or (0, NONE) if none exists), while preserving all valid
     * activated and standalone delete barriers.
     */
    public ArticleVersionBarrier resolveEffectiveVersionBarrier(
            long activeGeneration,
            long articleId,
            ArticleVersionBarrier rawBarrier,
            boolean repairInCurrentTx,
            long nowMs) {
        if (rawBarrier == null || rawBarrier.maxContentRevision() <= 0L) {
            return rawBarrier;
        }
        Set<Long> activatedGens = findActivatedGenerations(activeGeneration);
        List<Long> unactivatedGens = findUnactivatedCandidateGenerations(activatedGens);
        if (unactivatedGens.isEmpty()) {
            return rawBarrier;
        }

        ProjectionVersionSnapshot bestActivated = findHighestProjectionVersion(activatedGens, articleId);
        if (bestActivated != null
                && compareBarrierVersion(
                        bestActivated.contentRevision(), bestActivated.visibility(),
                        rawBarrier.maxContentRevision(), rawBarrier.lastVisibility()) >= 0) {
            return rawBarrier;
        }

        List<ProjectionVersionSnapshot> unactivatedProjections = findProjectionVersions(unactivatedGens, articleId);
        boolean explainedByUnactivatedCandidate = false;
        for (ProjectionVersionSnapshot candidate : unactivatedProjections) {
            if (candidate.contentRevision() == rawBarrier.maxContentRevision()
                    && candidate.visibility() == rawBarrier.lastVisibility()) {
                explainedByUnactivatedCandidate = true;
                break;
            }
            if (candidate.contentRevision() > rawBarrier.maxContentRevision()
                    && (rawBarrier.lastVisibility() == ArticleVisibility.PUBLIC
                    || existsAppliedRebuildEventForRevision(
                            candidate.indexGeneration(), articleId, rawBarrier.maxContentRevision()))) {
                explainedByUnactivatedCandidate = true;
                break;
            }
        }
        if (!explainedByUnactivatedCandidate) {
            return rawBarrier;
        }

        long healedRev = bestActivated == null ? 0L : bestActivated.contentRevision();
        ArticleVisibility healedVis = bestActivated == null ? ArticleVisibility.NONE : bestActivated.visibility();
        if (repairInCurrentTx) {
            jdbcTemplate.update(
                    """
                    UPDATE search_article_version_barrier
                    SET max_content_revision = ?,
                        last_visibility = ?,
                        updated_at_ms = ?
                    WHERE article_id = ?
                    """,
                    healedRev,
                    healedVis.name(),
                    nowMs,
                    articleId
            );
        }
        return new ArticleVersionBarrier(articleId, healedRev, healedVis, nowMs);
    }

    private Set<Long> findActivatedGenerations(long activeGeneration) {
        Set<Long> gens = new LinkedHashSet<>();
        gens.add(DEFAULT_GENERATION);
        if (activeGeneration > 0L) {
            gens.add(activeGeneration);
        }
        gens.addAll(jdbcTemplate.query(
                """
                SELECT active_generation AS gen FROM search_index_generation
                UNION
                SELECT previous_generation AS gen FROM search_index_generation WHERE previous_generation IS NOT NULL
                UNION
                SELECT source_generation AS gen FROM search_rebuild_job
                UNION
                SELECT candidate_generation AS gen FROM search_rebuild_job WHERE phase IN ('CACHE_INVALIDATING', 'COMPLETED')
                """,
                (rs, rowNum) -> rs.getLong("gen")
        ));
        return gens;
    }

    private List<Long> findUnactivatedCandidateGenerations(Set<Long> activatedGens) {
        List<Long> raw = jdbcTemplate.query(
                """
                SELECT DISTINCT candidate_generation
                FROM search_rebuild_job
                WHERE phase NOT IN ('CACHE_INVALIDATING', 'COMPLETED')
                """,
                (rs, rowNum) -> rs.getLong("candidate_generation")
        );
        List<Long> unactivated = new ArrayList<>();
        for (Long gen : raw) {
            if (gen != null && !activatedGens.contains(gen)) {
                unactivated.add(gen);
            }
        }
        return unactivated;
    }

    private ProjectionVersionSnapshot findHighestProjectionVersion(Set<Long> generations, long articleId) {
        List<ProjectionVersionSnapshot> rows = findProjectionVersions(new ArrayList<>(generations), articleId);
        ProjectionVersionSnapshot best = null;
        for (ProjectionVersionSnapshot row : rows) {
            if (best == null
                    || compareBarrierVersion(
                            row.contentRevision(), row.visibility(),
                            best.contentRevision(), best.visibility()) > 0) {
                best = row;
            }
        }
        return best;
    }

    private List<ProjectionVersionSnapshot> findProjectionVersions(List<Long> generations, long articleId) {
        if (generations == null || generations.isEmpty()) {
            return List.of();
        }
        String placeholders = generations.stream().map(g -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[generations.size() + 1];
        for (int i = 0; i < generations.size(); i++) {
            args[i] = generations.get(i);
        }
        args[generations.size()] = articleId;
        String sql = "SELECT index_generation, content_revision, visibility "
                + "FROM search_article_projection "
                + "WHERE index_generation IN (" + placeholders + ") "
                + "AND article_id = ? AND has_content = 1 AND content_revision > 0";
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ProjectionVersionSnapshot(
                        rs.getLong("index_generation"),
                        rs.getLong("content_revision"),
                        ArticleVisibility.valueOf(rs.getString("visibility"))
                ),
                args
        );
    }

    private boolean existsAppliedRebuildEventForRevision(
            long candidateGeneration, long articleId, long sourceRevision) {
        List<Integer> rows = jdbcTemplate.query(
                """
                SELECT 1
                FROM search_rebuild_event_log
                WHERE candidate_generation = ?
                  AND aggregate_id = ?
                  AND source_revision = ?
                  AND status = 'APPLIED'
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getInt(1),
                candidateGeneration,
                Long.toString(articleId),
                sourceRevision
        );
        return !rows.isEmpty();
    }

    private static int compareBarrierVersion(
            long leftRev, ArticleVisibility leftVis, long rightRev, ArticleVisibility rightVis) {
        int revCmp = Long.compare(leftRev, rightRev);
        if (revCmp != 0) {
            return revCmp;
        }
        return Integer.compare(visibilityRank(leftVis), visibilityRank(rightVis));
    }

    private record ProjectionVersionSnapshot(
            long indexGeneration, long contentRevision, ArticleVisibility visibility) {
    }

    // === Active Stream Inbox & Failure Log ===

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

    // === Generation-Scoped Article Projection ===

    public ArticleProjectionRecord ensureAndLockProjection(long articleId, long nowMs) {
        return ensureAndLockProjection(DEFAULT_GENERATION, articleId, nowMs);
    }

    public ArticleProjectionRecord ensureAndLockProjection(long indexGeneration, long articleId, long nowMs) {
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, updated_at_ms
                ) VALUES (?, ?, 0, 0, 'NONE', 0, 0, 0, ?)
                """,
                indexGeneration,
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
                WHERE index_generation = ? AND article_id = ?
                FOR UPDATE
                """,
                projectionRowMapper(),
                indexGeneration,
                articleId
        );
    }

    public Optional<ArticleProjectionRecord> findProjection(long articleId) {
        return findProjection(DEFAULT_GENERATION, articleId);
    }

    public Optional<ArticleProjectionRecord> findProjection(long indexGeneration, long articleId) {
        List<ArticleProjectionRecord> rows = jdbcTemplate.query(
                """
                SELECT article_id, has_content, content_revision, visibility,
                       counter_revision, view_count, projection_revision,
                       title, summary, content, category_id, category_name,
                       tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                FROM search_article_projection
                WHERE index_generation = ? AND article_id = ?
                """,
                projectionRowMapper(),
                indexGeneration,
                articleId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void updateProjection(ArticleProjectionRecord record) {
        updateProjection(DEFAULT_GENERATION, record);
    }

    public void updateProjection(long indexGeneration, ArticleProjectionRecord record) {
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
                WHERE index_generation = ? AND article_id = ?
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
                        indexGeneration,
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
                        Types.BIGINT,
                        Types.BIGINT
                }
        );
    }

    // === Generation-Scoped Index Tasks ===

    public void upsertPendingIndexTask(
            long articleId,
            long targetProjectionRevision,
            SearchIndexOperation operation,
            String payloadJson,
            int maxAttempts,
            long nowMs) {
        upsertPendingIndexTask(
                DEFAULT_GENERATION, "", articleId, targetProjectionRevision, operation, payloadJson, maxAttempts, nowMs);
    }

    public void upsertPendingIndexTask(
            long indexGeneration,
            String targetIndex,
            long articleId,
            long targetProjectionRevision,
            SearchIndexOperation operation,
            String payloadJson,
            int maxAttempts,
            long nowMs) {
        String safeTarget = targetIndex == null ? "" : targetIndex.trim();
        List<Long> existing = jdbcTemplate.query(
                "SELECT article_id FROM search_index_task WHERE index_generation = ? AND article_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("article_id"),
                indexGeneration,
                articleId
        );
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO search_index_task (
                        index_generation, article_id, target_index, target_projection_revision,
                        operation, payload_json, status, attempts, max_attempts, due_at_ms,
                        lease_until_ms, claim_generation, last_error, es_applied_revision,
                        cache_invalidation_status, cache_invalidation_attempts,
                        cache_invalidation_due_at_ms, cache_last_error, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, NULL, 0, NULL, 0, 'NONE', 0, NULL, NULL, ?)
                    """,
                    indexGeneration,
                    articleId,
                    safeTarget,
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
                    SET target_index = ?,
                        target_projection_revision = ?,
                        operation = ?,
                        payload_json = ?,
                        status = 'PENDING',
                        attempts = 0,
                        max_attempts = ?,
                        due_at_ms = ?,
                        lease_until_ms = NULL,
                        last_error = NULL,
                        updated_at_ms = ?
                    WHERE index_generation = ? AND article_id = ?
                    """,
                    safeTarget,
                    targetProjectionRevision,
                    operation.name(),
                    payloadJson,
                    maxAttempts,
                    nowMs,
                    nowMs,
                    indexGeneration,
                    articleId
            );
        }
    }

    public Optional<SearchIndexTaskRecord> findIndexTask(long articleId) {
        return findIndexTask(DEFAULT_GENERATION, articleId);
    }

    public Optional<SearchIndexTaskRecord> findIndexTask(long indexGeneration, long articleId) {
        List<SearchIndexTaskRecord> rows = jdbcTemplate.query(
                """
                SELECT index_generation, article_id, target_index, target_projection_revision,
                       operation, payload_json, status, attempts, max_attempts, due_at_ms,
                       lease_until_ms, claim_generation, last_error, es_applied_revision,
                       cache_invalidation_status, cache_invalidation_attempts,
                       cache_invalidation_due_at_ms, cache_last_error, updated_at_ms
                FROM search_index_task
                WHERE index_generation = ? AND article_id = ?
                """,
                indexTaskRowMapper(),
                indexGeneration,
                articleId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<SearchIndexTaskRecord> claimDueTasksInCurrentTx(int batchSize, long leaseDurationMs, long nowMs) {
        return claimDueTasksForGenerationInCurrentTx(DEFAULT_GENERATION, "", batchSize, leaseDurationMs, nowMs);
    }

    public List<SearchIndexTaskRecord> claimDueOnlineTasksInCurrentTx(
            String aliasName, String defaultPhysicalIndex, int batchSize, long leaseDurationMs, long nowMs) {
        IndexGenerationState gen = ensureAndLockGeneration(aliasName, defaultPhysicalIndex, nowMs);
        if (!gen.isNormal()) {
            return List.of();
        }
        return claimDueTasksForGenerationInCurrentTx(
                gen.activeGeneration(), gen.activePhysicalIndex(), batchSize, leaseDurationMs, nowMs);
    }

    public Optional<SearchIndexTaskRecord> claimSingleOnlineTaskInCurrentTx(
            String aliasName, String defaultPhysicalIndex, long articleId, long leaseDurationMs, long nowMs) {
        IndexGenerationState gen = ensureAndLockGeneration(aliasName, defaultPhysicalIndex, nowMs);
        if (!gen.isNormal()) {
            return Optional.empty();
        }
        long indexGeneration = gen.activeGeneration();
        List<SearchIndexTaskRecord> locked = jdbcTemplate.query(
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
                indexTaskRowMapper(),
                indexGeneration,
                articleId
        );
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        SearchIndexTaskRecord current = locked.get(0);
        boolean eligiblePending = current.status() == SearchIndexTaskStatus.PENDING && current.dueAtMs() <= nowMs;
        boolean eligibleExpiredLease = current.status() == SearchIndexTaskStatus.LEASED
                && current.leaseUntilMs() != null && current.leaseUntilMs() <= nowMs;
        if (!eligiblePending && !eligibleExpiredLease) {
            return Optional.empty();
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
                    WHERE index_generation = ? AND article_id = ?
                    """,
                    nextAttempts,
                    nowMs,
                    indexGeneration,
                    articleId
            );
            return Optional.empty();
        }
        String boundTargetIndex = (current.targetIndex() != null && !current.targetIndex().isBlank())
                ? current.targetIndex()
                : (gen.activePhysicalIndex() == null ? "" : gen.activePhysicalIndex().trim());
        long nextGeneration = current.claimGeneration() + 1L;
        long leaseUntilMs = nowMs + Math.max(1_000L, leaseDurationMs);
        jdbcTemplate.update(
                """
                UPDATE search_index_task
                SET target_index = ?,
                    status = 'LEASED',
                    attempts = ?,
                    lease_until_ms = ?,
                    claim_generation = ?,
                    updated_at_ms = ?
                WHERE index_generation = ? AND article_id = ?
                """,
                boundTargetIndex,
                nextAttempts,
                leaseUntilMs,
                nextGeneration,
                nowMs,
                indexGeneration,
                articleId
        );
        return Optional.of(new SearchIndexTaskRecord(
                current.indexGeneration(),
                current.articleId(),
                boundTargetIndex,
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

    public List<SearchIndexTaskRecord> claimDueTasksForGenerationInCurrentTx(
            long indexGeneration, String defaultTargetIndex, int batchSize, long leaseDurationMs, long nowMs) {
        List<Long> candidateIds = jdbcTemplate.query(
                """
                SELECT article_id
                FROM search_index_task
                WHERE index_generation = ?
                  AND ((status = 'PENDING' AND due_at_ms <= ?)
                    OR (status = 'LEASED' AND lease_until_ms <= ?))
                ORDER BY due_at_ms ASC, article_id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getLong("article_id"),
                indexGeneration,
                nowMs,
                nowMs,
                Math.max(1, batchSize)
        );
        List<SearchIndexTaskRecord> claimed = new ArrayList<>();
        for (Long articleId : candidateIds) {
            List<SearchIndexTaskRecord> locked = jdbcTemplate.query(
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
                    indexTaskRowMapper(),
                    indexGeneration,
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
                        WHERE index_generation = ? AND article_id = ?
                        """,
                        nextAttempts,
                        nowMs,
                        indexGeneration,
                        articleId
                );
                continue;
            }

            String boundTargetIndex = (current.targetIndex() != null && !current.targetIndex().isBlank())
                    ? current.targetIndex()
                    : (defaultTargetIndex == null ? "" : defaultTargetIndex.trim());
            long nextGeneration = current.claimGeneration() + 1L;
            long leaseUntilMs = nowMs + Math.max(1_000L, leaseDurationMs);
            jdbcTemplate.update(
                    """
                    UPDATE search_index_task
                    SET target_index = ?,
                        status = 'LEASED',
                        attempts = ?,
                        lease_until_ms = ?,
                        claim_generation = ?,
                        updated_at_ms = ?
                    WHERE index_generation = ? AND article_id = ?
                    """,
                    boundTargetIndex,
                    nextAttempts,
                    leaseUntilMs,
                    nextGeneration,
                    nowMs,
                    indexGeneration,
                    articleId
            );
            claimed.add(new SearchIndexTaskRecord(
                    current.indexGeneration(),
                    current.articleId(),
                    boundTargetIndex,
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
            long indexGeneration,
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
                WHERE index_generation = ?
                  AND article_id = ?
                  AND target_projection_revision = ?
                  AND claim_generation = ?
                  AND status = 'LEASED'
                """,
                targetProjectionRevision,
                targetProjectionRevision,
                cacheStatus,
                cacheDueAt,
                nowMs,
                indexGeneration,
                articleId,
                targetProjectionRevision,
                claimGeneration
        );
        return updated == 1;
    }

    public boolean markSupersededConditionally(
            long indexGeneration,
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
                WHERE index_generation = ?
                  AND article_id = ?
                  AND target_projection_revision = ?
                  AND claim_generation = ?
                  AND status = 'LEASED'
                """,
                nowMs,
                indexGeneration,
                articleId,
                targetProjectionRevision,
                claimGeneration
        );
        return updated == 1;
    }

    public boolean markRetryOrDeadConditionallyInCurrentTx(
            long indexGeneration,
            long articleId,
            long targetProjectionRevision,
            long claimGeneration,
            String errorMessage,
            long baseRetryDelayMs,
            long maxRetryDelayMs,
            long nowMs) {
        List<SearchIndexTaskRecord> locked = jdbcTemplate.query(
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
                indexTaskRowMapper(),
                indexGeneration,
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
                    WHERE index_generation = ?
                      AND article_id = ?
                      AND target_projection_revision = ?
                      AND claim_generation = ?
                      AND status = 'LEASED'
                    """,
                    nextAttempts,
                    safeError,
                    nowMs,
                    indexGeneration,
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
                WHERE index_generation = ?
                  AND article_id = ?
                  AND target_projection_revision = ?
                  AND claim_generation = ?
                  AND status = 'LEASED'
                """,
                nextAttempts,
                nowMs + delayMs,
                safeError,
                nowMs,
                indexGeneration,
                articleId,
                targetProjectionRevision,
                claimGeneration
        );
        return true;
    }

    public boolean markCacheInvalidationDoneConditionally(
            long indexGeneration,
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
                WHERE index_generation = ?
                  AND article_id = ?
                  AND es_applied_revision = ?
                  AND cache_invalidation_status = 'PENDING'
                """,
                nowMs,
                indexGeneration,
                articleId,
                expectedEsAppliedRevision
        );
        return updated == 1;
    }

    public boolean markCacheInvalidationRetryConditionally(
            long indexGeneration,
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
                WHERE index_generation = ?
                  AND article_id = ?
                  AND es_applied_revision = ?
                  AND cache_invalidation_status = 'PENDING'
                """,
                nextAttempts,
                nowMs + delayMs,
                boundedError(errorMessage),
                nowMs,
                indexGeneration,
                articleId,
                expectedEsAppliedRevision
        );
        return updated == 1;
    }

    public List<CacheCompensationCandidate> findDueCacheInvalidations(int batchSize, long nowMs) {
        return jdbcTemplate.query(
                """
                SELECT index_generation, article_id, es_applied_revision, cache_invalidation_attempts
                FROM search_index_task
                WHERE cache_invalidation_status = 'PENDING'
                  AND cache_invalidation_due_at_ms IS NOT NULL
                  AND cache_invalidation_due_at_ms <= ?
                ORDER BY cache_invalidation_due_at_ms ASC, index_generation ASC, article_id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new CacheCompensationCandidate(
                        rs.getLong("index_generation"),
                        rs.getLong("article_id"),
                        rs.getLong("es_applied_revision"),
                        rs.getInt("cache_invalidation_attempts")
                ),
                nowMs,
                Math.max(1, batchSize)
        );
    }

    public long countIncompleteTasks(long indexGeneration) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM search_index_task
                WHERE index_generation = ? AND status <> 'DONE'
                """,
                Long.class,
                indexGeneration
        );
        return count == null ? 0L : count;
    }

    public long countDeadTasks(long indexGeneration) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM search_index_task
                WHERE index_generation = ? AND status = 'DEAD'
                """,
                Long.class,
                indexGeneration
        );
        return count == null ? 0L : count;
    }

    private RowMapper<IndexGenerationState> generationRowMapper() {
        return (rs, rowNum) -> new IndexGenerationState(
                rs.getString("alias_name"),
                rs.getLong("active_generation"),
                rs.getString("active_physical_index"),
                rs.getInt("require_cold_visibility_guard") != 0,
                IndexGenerationState.CutoverState.valueOf(rs.getString("cutover_state")),
                rs.getString("cutover_job_id"),
                nullableLong(rs, "previous_generation"),
                rs.getString("previous_physical_index"),
                nullableLong(rs, "target_generation"),
                rs.getString("target_physical_index"),
                nullableLong(rs, "cutover_started_at_ms"),
                CacheInvalidationStatus.valueOf(rs.getString("active_cache_invalidation_status")),
                rs.getInt("active_cache_invalidation_attempts"),
                nullableLong(rs, "active_cache_invalidation_due_at_ms"),
                rs.getString("active_cache_last_error"),
                rs.getString("active_cutover_bypass_owner"),
                rs.getLong("updated_at_ms")
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
                rs.getLong("index_generation"),
                rs.getLong("article_id"),
                rs.getString("target_index"),
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

    public record CacheCompensationCandidate(
            long indexGeneration, long articleId, long esAppliedRevision, int attempts) {
    }

    public record GenerationCacheCompensationCandidate(
            String aliasName, long activeGeneration, int attempts, String bypassOwner) {
    }
}
