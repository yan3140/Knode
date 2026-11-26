package com.knode.search.rebuild.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.event.ParsedContentEvent;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "knode.search.rebuild", name = "enabled", havingValue = "true")
public class SearchRebuildRepository {
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final SearchConsumerRepository consumerRepository;
    private final ObjectMapper objectMapper;

    public SearchRebuildRepository(
            @Qualifier("searchConsumerJdbcTemplate") JdbcTemplate jdbcTemplate,
            SearchConsumerRepository consumerRepository,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.consumerRepository = consumerRepository;
        this.objectMapper = objectMapper;
    }

    public RebuildJobRecord startJobInCurrentTx(
            String jobId,
            String aliasName,
            String defaultInitialPhysicalIndex,
            String clientRequestId,
            String leaseOwner,
            long leaseDurationMs,
            long nowMs) {
        IndexGenerationState gen = consumerRepository.ensureAndLockGeneration(
                aliasName, defaultInitialPhysicalIndex, nowMs);
        if (!gen.isNormal()) {
            throw new IllegalStateException("Cannot start rebuild while alias " + aliasName
                    + " is in cutover state " + gen.cutoverState());
        }
        Optional<RebuildJobRecord> existingActive = findActiveJob(aliasName);
        if (existingActive.isPresent()) {
            throw new IllegalStateException("An active rebuild job already exists for alias "
                    + aliasName + ": " + existingActive.get().jobId());
        }

        long maxCandidateGen = findMaxAllocatedGeneration(aliasName, gen.activeGeneration());
        long candidateGeneration = maxCandidateGen + 1L;
        String candidatePhysicalIndex = physicalIndexName(aliasName, candidateGeneration);

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO search_rebuild_job (
                        job_id, alias_name, active_lock_key, client_request_id,
                        snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                        last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                        source_generation, source_physical_index,
                        candidate_generation, candidate_physical_index,
                        phase, lease_owner, lease_until_ms, lease_generation,
                        alias_switch_uncertain, switch_attempts,
                        created_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, ?, ?, ?, ?, 'INIT', ?, ?, 1, 0, 0, ?, ?)
                    """,
                    jobId,
                    aliasName,
                    aliasName,
                    clientRequestId,
                    gen.activeGeneration(),
                    gen.activePhysicalIndex(),
                    candidateGeneration,
                    candidatePhysicalIndex,
                    leaseOwner,
                    nowMs + Math.max(5_000L, leaseDurationMs),
                    nowMs,
                    nowMs
            );
        } catch (DuplicateKeyException dup) {
            throw new IllegalStateException("Concurrent rebuild job already active for alias " + aliasName, dup);
        }
        return findJob(jobId).orElseThrow();
    }

    public Optional<RebuildJobRecord> findJob(String jobId) {
        List<RebuildJobRecord> rows = jdbcTemplate.query(
                selectJobSql("WHERE job_id = ?"),
                jobRowMapper(),
                jobId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<RebuildJobRecord> findActiveJob(String aliasName) {
        List<RebuildJobRecord> rows = jdbcTemplate.query(
                selectJobSql("WHERE active_lock_key = ?"),
                jobRowMapper(),
                aliasName
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<RebuildJobRecord> acquireOrRenewLeaseInCurrentTx(
            String jobId, String owner, long leaseDurationMs, long nowMs) {
        List<RebuildJobRecord> locked = jdbcTemplate.query(
                selectJobSql("WHERE job_id = ? FOR UPDATE"),
                jobRowMapper(),
                jobId
        );
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        RebuildJobRecord current = locked.get(0);
        if (current.phase().isTerminal()) {
            return Optional.of(current);
        }
        boolean ownedOrExpired = current.leaseOwner() == null
                || current.leaseOwner().equals(owner)
                || current.leaseUntilMs() == null
                || current.leaseUntilMs() <= nowMs;
        if (!ownedOrExpired) {
            return Optional.empty();
        }
        long newLeaseUntil = nowMs + Math.max(5_000L, leaseDurationMs);
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET lease_owner = ?,
                    lease_until_ms = ?,
                    lease_generation = lease_generation + 1,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                owner,
                newLeaseUntil,
                nowMs,
                jobId
        );
        return findJob(jobId);
    }

    /**
     * Acquires an exclusive row lock on search_rebuild_job and verifies that the lease still belongs
     * to expectedOwner, matches expectedJob.leaseGeneration(), has not expired, remains in expectedPhase,
     * and (when requested) matches the expected snapshot or incremental cursor before any local writeback.
     */
    public RebuildJobRecord lockAndVerifyLeaseInCurrentTx(
            RebuildJobRecord expectedJob,
            String expectedOwner,
            RebuildPhase expectedPhase,
            boolean verifySnapshotCursor,
            boolean verifyIncrementalCursor,
            long nowMs) {
        List<RebuildJobRecord> locked = jdbcTemplate.query(
                selectJobSql("WHERE job_id = ? FOR UPDATE"),
                jobRowMapper(),
                expectedJob.jobId()
        );
        if (locked.isEmpty()) {
            throw new StaleRebuildLeaseException(expectedJob.jobId(), "JOB_NOT_FOUND");
        }
        RebuildJobRecord current = locked.get(0);
        if (current.leaseOwner() == null || !current.leaseOwner().equals(expectedOwner)) {
            throw new StaleRebuildLeaseException(expectedJob.jobId(),
                    "LEASE_OWNER_MISMATCH:expected=" + expectedOwner + ",actual=" + current.leaseOwner());
        }
        if (current.leaseGeneration() != expectedJob.leaseGeneration()) {
            throw new StaleRebuildLeaseException(expectedJob.jobId(),
                    "LEASE_GENERATION_MISMATCH:expected=" + expectedJob.leaseGeneration()
                            + ",actual=" + current.leaseGeneration());
        }
        if (current.leaseUntilMs() == null || current.leaseUntilMs() <= nowMs) {
            throw new StaleRebuildLeaseException(expectedJob.jobId(),
                    "LEASE_EXPIRED:leaseUntilMs=" + current.leaseUntilMs() + ",nowMs=" + nowMs);
        }
        if (expectedPhase != null && current.phase() != expectedPhase) {
            throw new StaleRebuildLeaseException(expectedJob.jobId(),
                    "PHASE_DRIFTED:expected=" + expectedPhase + ",actual=" + current.phase());
        }
        if (verifySnapshotCursor) {
            if (!Objects.equals(current.snapshotPageCursor(), expectedJob.snapshotPageCursor())
                    || current.snapshotPagesImported() != expectedJob.snapshotPagesImported()) {
                throw new StaleRebuildLeaseException(expectedJob.jobId(),
                        "SNAPSHOT_CURSOR_DRIFTED:expectedPages=" + expectedJob.snapshotPagesImported()
                                + ",actualPages=" + current.snapshotPagesImported());
            }
        }
        if (verifyIncrementalCursor) {
            if (!Objects.equals(current.incrementalCursor(), expectedJob.incrementalCursor())
                    || current.lastCommitSequence() != expectedJob.lastCommitSequence()) {
                throw new StaleRebuildLeaseException(expectedJob.jobId(),
                        "INCREMENTAL_CURSOR_DRIFTED:expectedSeq=" + expectedJob.lastCommitSequence()
                                + ",actualSeq=" + current.lastCommitSequence());
            }
        }
        return current;
    }

    public void markAliasSwitchUncertainInCurrentTx(
            RebuildJobRecord job,
            String owner,
            boolean uncertain,
            String errorMessage,
            long nowMs) {
        lockAndVerifyLeaseInCurrentTx(job, owner, RebuildPhase.SWITCHING_ALIAS, false, false, nowMs);
        if (errorMessage != null) {
            jdbcTemplate.update(
                    """
                    UPDATE search_rebuild_job
                    SET alias_switch_uncertain = ?,
                        last_error = ?,
                        updated_at_ms = ?
                    WHERE job_id = ?
                    """,
                    uncertain ? 1 : 0,
                    boundedError(errorMessage),
                    nowMs,
                    job.jobId()
            );
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE search_rebuild_job
                    SET alias_switch_uncertain = ?,
                        updated_at_ms = ?
                    WHERE job_id = ?
                    """,
                    uncertain ? 1 : 0,
                    nowMs,
                    job.jobId()
            );
        }
    }

    public void updateSnapshotHandleInCurrentTx(
            String jobId,
            String snapshotId,
            String incrementalCursor,
            long snapshotExpiresAtMs,
            long nowMs) {
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET snapshot_id = ?,
                    incremental_cursor = ?,
                    snapshot_expires_at_ms = ?,
                    phase = 'SNAPSHOT_IMPORTING',
                    last_error = NULL,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                snapshotId,
                incrementalCursor,
                snapshotExpiresAtMs,
                nowMs,
                jobId
        );
    }

    public void updateSnapshotPageCheckpointInCurrentTx(
            String jobId,
            String nextCursor,
            boolean done,
            int itemsInPage,
            long nowMs) {
        RebuildPhase nextPhase = done ? RebuildPhase.INCREMENTAL_CATCHUP : RebuildPhase.SNAPSHOT_IMPORTING;
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET snapshot_page_cursor = ?,
                    snapshot_done = ?,
                    snapshot_pages_imported = snapshot_pages_imported + 1,
                    snapshot_items_imported = snapshot_items_imported + ?,
                    phase = ?,
                    last_error = NULL,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                nextCursor,
                done ? 1 : 0,
                itemsInPage,
                nextPhase.name(),
                nowMs,
                jobId
        );
    }

    public boolean insertRebuildEventIfAbsent(
            long candidateGeneration,
            long commitSequence,
            ParsedContentEvent event,
            String status,
            long nowMs) {
        int rows = jdbcTemplate.update(
                """
                INSERT IGNORE INTO search_rebuild_event_log (
                    candidate_generation, event_id, commit_sequence, event_type,
                    aggregate_type, aggregate_id, source_revision, status, applied_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                candidateGeneration,
                event.eventId(),
                commitSequence,
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.sourceRevision(),
                status,
                nowMs
        );
        return rows == 1;
    }

    public void updateRebuildEventStatus(long candidateGeneration, String eventId, String status, long nowMs) {
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_event_log
                SET status = ?, applied_at_ms = ?
                WHERE candidate_generation = ? AND event_id = ?
                """,
                status,
                nowMs,
                candidateGeneration,
                eventId
        );
    }

    public void updateIncrementalCheckpointInCurrentTx(
            String jobId,
            String nextCursor,
            long cursorExpiresAtMs,
            long highestCommitSeq,
            int commitsApplied,
            int eventsApplied,
            RebuildPhase nextPhase,
            long nowMs) {
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET incremental_cursor = ?,
                    incremental_cursor_expires_at_ms = ?,
                    last_commit_sequence = CASE
                        WHEN last_commit_sequence < ? THEN ?
                        ELSE last_commit_sequence
                    END,
                    incremental_commits_applied = incremental_commits_applied + ?,
                    incremental_events_applied = incremental_events_applied + ?,
                    phase = ?,
                    last_error = NULL,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                nextCursor,
                cursorExpiresAtMs,
                highestCommitSeq,
                highestCommitSeq,
                commitsApplied,
                eventsApplied,
                nextPhase.name(),
                nowMs,
                jobId
        );
    }

    public void updatePhaseInCurrentTx(String jobId, RebuildPhase nextPhase, String errorMessage, long nowMs) {
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET phase = ?,
                    last_error = ?,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                nextPhase.name(),
                errorMessage == null ? null : boundedError(errorMessage),
                nowMs,
                jobId
        );
    }

    public void recordTransientErrorInCurrentTx(String jobId, String errorMessage, long nowMs) {
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET last_error = ?,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                boundedError(errorMessage),
                nowMs,
                jobId
        );
    }

    public void markJobFailedInCurrentTx(String jobId, String aliasName, String errorMessage, long nowMs) {
        // If this job was holding a pre-switch pause, release it safely back to source generation.
        jdbcTemplate.update(
                """
                UPDATE search_index_generation
                SET cutover_state = 'NORMAL',
                    cutover_job_id = NULL,
                    target_generation = NULL,
                    target_physical_index = NULL,
                    cutover_started_at_ms = NULL,
                    updated_at_ms = ?
                WHERE alias_name = ?
                  AND cutover_job_id = ?
                  AND cutover_state = 'CUTOVER_PAUSED'
                """,
                nowMs,
                aliasName,
                jobId
        );
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET phase = 'FAILED',
                    active_lock_key = NULL,
                    alias_switch_uncertain = 0,
                    last_error = ?,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                boundedError(errorMessage),
                nowMs,
                jobId
        );
    }

    /**
     * Atomically marks an expired pre-switch candidate job EXPIRED_SUPERSEDED, releases any pre-switch
     * cutover pause (preserving the active alias/generation), and spawns a fresh replacement job with a
     * new clientRequestId and incremented candidateGeneration.
     */
    public RebuildJobRecord expireAndRestartJobInCurrentTx(
            RebuildJobRecord expiredJob,
            String newJobId,
            String newClientRequestId,
            String leaseOwner,
            long leaseDurationMs,
            String reason,
            long nowMs) {
        IndexGenerationState gen = consumerRepository.ensureAndLockGeneration(
                expiredJob.aliasName(), expiredJob.sourcePhysicalIndex(), nowMs);
        if (gen.cutoverState() == IndexGenerationState.CutoverState.SWITCHING_ALIAS) {
            throw new IllegalStateException("Cannot expire/restart job once SWITCHING_ALIAS has started");
        }
        if (expiredJob.jobId().equals(gen.cutoverJobId())
                && gen.cutoverState() == IndexGenerationState.CutoverState.CUTOVER_PAUSED) {
            jdbcTemplate.update(
                    """
                    UPDATE search_index_generation
                    SET cutover_state = 'NORMAL',
                        cutover_job_id = NULL,
                        target_generation = NULL,
                        target_physical_index = NULL,
                        cutover_started_at_ms = NULL,
                        updated_at_ms = ?
                    WHERE alias_name = ?
                    """,
                    nowMs,
                    expiredJob.aliasName()
            );
        }

        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET phase = 'EXPIRED_SUPERSEDED',
                    active_lock_key = NULL,
                    alias_switch_uncertain = 0,
                    superseded_by_job_id = ?,
                    last_error = ?,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                newJobId,
                boundedError(reason),
                nowMs,
                expiredJob.jobId()
        );

        long nextCandidateGeneration = findMaxAllocatedGeneration(expiredJob.aliasName(), gen.activeGeneration()) + 1L;
        String nextCandidateIndex = physicalIndexName(expiredJob.aliasName(), nextCandidateGeneration);
        jdbcTemplate.update(
                """
                INSERT INTO search_rebuild_job (
                    job_id, alias_name, active_lock_key, client_request_id,
                    snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                    last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                    source_generation, source_physical_index,
                    candidate_generation, candidate_physical_index,
                    phase, lease_owner, lease_until_ms, lease_generation,
                    alias_switch_uncertain, switch_attempts,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, ?, ?, ?, ?, 'INIT', ?, ?, 1, 0, 0, ?, ?)
                """,
                newJobId,
                expiredJob.aliasName(),
                expiredJob.aliasName(),
                newClientRequestId,
                gen.activeGeneration(),
                gen.activePhysicalIndex(),
                nextCandidateGeneration,
                nextCandidateIndex,
                leaseOwner,
                nowMs + Math.max(5_000L, leaseDurationMs),
                nowMs,
                nowMs
        );
        return findJob(newJobId).orElseThrow();
    }

    public void enterCutoverPauseInCurrentTx(RebuildJobRecord job, long nowMs) {
        IndexGenerationState gen = consumerRepository.ensureAndLockGeneration(
                job.aliasName(), job.sourcePhysicalIndex(), nowMs);
        if (gen.activeGeneration() != job.sourceGeneration()) {
            throw new IllegalStateException("Active generation drifted from " + job.sourceGeneration()
                    + " to " + gen.activeGeneration() + " before cutover");
        }
        if (gen.cutoverState() == IndexGenerationState.CutoverState.NORMAL
                || (gen.cutoverState() == IndexGenerationState.CutoverState.CUTOVER_PAUSED
                && job.jobId().equals(gen.cutoverJobId()))) {
            jdbcTemplate.update(
                    """
                    UPDATE search_index_generation
                    SET cutover_state = 'CUTOVER_PAUSED',
                        cutover_job_id = ?,
                        target_generation = ?,
                        target_physical_index = ?,
                        cutover_started_at_ms = COALESCE(cutover_started_at_ms, ?),
                        updated_at_ms = ?
                    WHERE alias_name = ?
                    """,
                    job.jobId(),
                    job.candidateGeneration(),
                    job.candidatePhysicalIndex(),
                    nowMs,
                    nowMs,
                    job.aliasName()
            );
            jdbcTemplate.update(
                    """
                    UPDATE search_rebuild_job
                    SET phase = 'FINAL_CATCHUP',
                        last_error = NULL,
                        updated_at_ms = ?
                    WHERE job_id = ?
                    """,
                    nowMs,
                    job.jobId()
            );
            return;
        }
        throw new IllegalStateException("Unexpected cutover state " + gen.cutoverState()
                + " held by " + gen.cutoverJobId());
    }

    public void enterSwitchingAliasInCurrentTx(RebuildJobRecord job, long nowMs) {
        IndexGenerationState gen = consumerRepository.ensureAndLockGeneration(
                job.aliasName(), job.sourcePhysicalIndex(), nowMs);
        if (!job.jobId().equals(gen.cutoverJobId())) {
            throw new IllegalStateException("Cutover gate not owned by job " + job.jobId());
        }
        jdbcTemplate.update(
                """
                UPDATE search_index_generation
                SET cutover_state = 'SWITCHING_ALIAS',
                    updated_at_ms = ?
                WHERE alias_name = ? AND cutover_job_id = ?
                """,
                nowMs,
                job.aliasName(),
                job.jobId()
        );
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET phase = 'SWITCHING_ALIAS',
                    alias_switch_uncertain = 0,
                    switch_attempts = switch_attempts + 1,
                    last_error = NULL,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                nowMs,
                job.jobId()
        );
    }

    public void incrementSwitchAttemptInCurrentTx(String jobId, long nowMs) {
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET switch_attempts = switch_attempts + 1,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                nowMs,
                jobId
        );
    }

    /**
     * Reverts a confirmed pre-switch alias failure so the old alias/generation resumes normal online traffic.
     */
    public void revertConfirmedPreSwitchFailureInCurrentTx(
            RebuildJobRecord job, boolean terminalFailure, String errorMessage, long nowMs) {
        jdbcTemplate.update(
                """
                UPDATE search_index_generation
                SET cutover_state = 'NORMAL',
                    cutover_job_id = NULL,
                    target_generation = NULL,
                    target_physical_index = NULL,
                    cutover_started_at_ms = NULL,
                    updated_at_ms = ?
                WHERE alias_name = ?
                  AND cutover_job_id = ?
                  AND active_generation = ?
                """,
                nowMs,
                job.aliasName(),
                job.jobId(),
                job.sourceGeneration()
        );
        if (terminalFailure) {
            jdbcTemplate.update(
                    """
                    UPDATE search_rebuild_job
                    SET phase = 'FAILED',
                        active_lock_key = NULL,
                        alias_switch_uncertain = 0,
                        last_error = ?,
                        updated_at_ms = ?
                    WHERE job_id = ?
                    """,
                    boundedError(errorMessage),
                    nowMs,
                    job.jobId()
            );
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE search_rebuild_job
                    SET phase = 'CUTOVER_PAUSING',
                        alias_switch_uncertain = 0,
                        last_error = ?,
                        updated_at_ms = ?
                    WHERE job_id = ?
                    """,
                    boundedError(errorMessage),
                    nowMs,
                    job.jobId()
            );
        }
    }

    /**
     * Atomically flips local active generation to the switched candidate physical index,
     * enables the post-rebuild cold-article visibility guard, restores cutover_state to NORMAL,
     * and records pending cache epoch invalidation when cache is enabled.
     */
    public void activateCandidateGenerationInCurrentTx(
            RebuildJobRecord job, boolean cacheEnabled, long nowMs) {
        consumerRepository.ensureAndLockGeneration(job.aliasName(), job.sourcePhysicalIndex(), nowMs);
        String cacheStatus = cacheEnabled
                ? CacheInvalidationStatus.PENDING.name()
                : CacheInvalidationStatus.DONE.name();
        Long cacheDueAt = cacheEnabled ? nowMs : null;

        jdbcTemplate.update(
                """
                UPDATE search_index_generation
                SET active_generation = ?,
                    active_physical_index = ?,
                    require_cold_visibility_guard = 1,
                    cutover_state = 'NORMAL',
                    cutover_job_id = NULL,
                    previous_generation = ?,
                    previous_physical_index = ?,
                    target_generation = NULL,
                    target_physical_index = NULL,
                    cutover_started_at_ms = NULL,
                    active_cache_invalidation_status = ?,
                    active_cache_invalidation_attempts = 0,
                    active_cache_invalidation_due_at_ms = ?,
                    active_cache_last_error = NULL,
                    updated_at_ms = ?
                WHERE alias_name = ?
                """,
                job.candidateGeneration(),
                job.candidatePhysicalIndex(),
                job.sourceGeneration(),
                job.sourcePhysicalIndex(),
                cacheStatus,
                cacheDueAt,
                nowMs,
                job.aliasName()
        );
        if (cacheEnabled) {
            jdbcTemplate.update(
                    """
                    UPDATE search_rebuild_job
                    SET phase = 'CACHE_INVALIDATING',
                        alias_switch_uncertain = 0,
                        last_error = NULL,
                        updated_at_ms = ?
                    WHERE job_id = ?
                    """,
                    nowMs,
                    job.jobId()
            );
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE search_rebuild_job
                    SET phase = 'COMPLETED',
                        active_lock_key = NULL,
                        alias_switch_uncertain = 0,
                        last_error = NULL,
                        updated_at_ms = ?
                    WHERE job_id = ?
                    """,
                    nowMs,
                    job.jobId()
            );
        }
    }

    public void markJobCompletedAfterCacheInvalidationInCurrentTx(
            RebuildJobRecord job, long nowMs) {
        consumerRepository.markGenerationCacheInvalidationDone(
                job.aliasName(), job.candidateGeneration(), nowMs);
        jdbcTemplate.update(
                """
                UPDATE search_rebuild_job
                SET phase = 'COMPLETED',
                    active_lock_key = NULL,
                    alias_switch_uncertain = 0,
                    last_error = NULL,
                    updated_at_ms = ?
                WHERE job_id = ?
                """,
                nowMs,
                job.jobId()
        );
    }

    // === Candidate Verification Queries ===

    public long countCandidateProjections(long candidateGeneration, boolean publicOnly) {
        String sql = publicOnly
                ? """
                  SELECT COUNT(*)
                  FROM search_article_projection
                  WHERE index_generation = ? AND has_content = 1 AND visibility = 'PUBLIC'
                  """
                : """
                  SELECT COUNT(*)
                  FROM search_article_projection
                  WHERE index_generation = ? AND has_content = 1 AND visibility IN ('WITHDRAWN', 'DELETED')
                  """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, candidateGeneration);
        return count == null ? 0L : count;
    }

    public List<ArticleProjectionRecord> sampleCandidateProjections(
            long candidateGeneration, boolean publicOnly, int limit) {
        String sql = publicOnly
                ? """
                  SELECT article_id, has_content, content_revision, visibility,
                         counter_revision, view_count, projection_revision,
                         title, summary, content, category_id, category_name,
                         tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                  FROM search_article_projection
                  WHERE index_generation = ? AND has_content = 1 AND visibility = 'PUBLIC'
                  ORDER BY article_id ASC
                  LIMIT ?
                  """
                : """
                  SELECT article_id, has_content, content_revision, visibility,
                         counter_revision, view_count, projection_revision,
                         title, summary, content, category_id, category_name,
                         tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                  FROM search_article_projection
                  WHERE index_generation = ? AND has_content = 1 AND visibility IN ('WITHDRAWN', 'DELETED')
                  ORDER BY article_id ASC
                  LIMIT ?
                  """;
        return jdbcTemplate.query(sql, projectionRowMapper(), candidateGeneration, Math.max(1, limit));
    }

    public long countRebuildEventLogs(long candidateGeneration) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_rebuild_event_log WHERE candidate_generation = ?",
                Long.class,
                candidateGeneration
        );
        return count == null ? 0L : count;
    }

    public static String physicalIndexName(String aliasName, long generation) {
        return aliasName.trim() + "_g" + generation;
    }

    private long findMaxAllocatedGeneration(String aliasName, long activeGeneration) {
        Long maxJobGen = jdbcTemplate.queryForObject(
                "SELECT MAX(candidate_generation) FROM search_rebuild_job WHERE alias_name = ?",
                Long.class,
                aliasName
        );
        long maxVal = activeGeneration;
        if (maxJobGen != null && maxJobGen > maxVal) {
            maxVal = maxJobGen;
        }
        return maxVal;
    }

    private static String selectJobSql(String whereClause) {
        return """
                SELECT job_id, alias_name, active_lock_key, client_request_id,
                       snapshot_id, snapshot_expires_at_ms, snapshot_page_cursor,
                       snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                       incremental_cursor, incremental_cursor_expires_at_ms,
                       last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                       source_generation, source_physical_index,
                       candidate_generation, candidate_physical_index,
                       phase, lease_owner, lease_until_ms, lease_generation,
                       alias_switch_uncertain, switch_attempts,
                       last_error, superseded_by_job_id, created_at_ms, updated_at_ms
                FROM search_rebuild_job
                """ + " " + whereClause;
    }

    private RowMapper<RebuildJobRecord> jobRowMapper() {
        return (rs, rowNum) -> new RebuildJobRecord(
                rs.getString("job_id"),
                rs.getString("alias_name"),
                rs.getString("active_lock_key"),
                rs.getString("client_request_id"),
                rs.getString("snapshot_id"),
                nullableLong(rs, "snapshot_expires_at_ms"),
                rs.getString("snapshot_page_cursor"),
                rs.getInt("snapshot_done") != 0,
                rs.getInt("snapshot_pages_imported"),
                rs.getLong("snapshot_items_imported"),
                rs.getString("incremental_cursor"),
                nullableLong(rs, "incremental_cursor_expires_at_ms"),
                rs.getLong("last_commit_sequence"),
                rs.getLong("incremental_commits_applied"),
                rs.getLong("incremental_events_applied"),
                rs.getLong("source_generation"),
                rs.getString("source_physical_index"),
                rs.getLong("candidate_generation"),
                rs.getString("candidate_physical_index"),
                RebuildPhase.valueOf(rs.getString("phase")),
                rs.getString("lease_owner"),
                nullableLong(rs, "lease_until_ms"),
                rs.getLong("lease_generation"),
                rs.getInt("alias_switch_uncertain") != 0,
                rs.getInt("switch_attempts"),
                rs.getString("last_error"),
                rs.getString("superseded_by_job_id"),
                rs.getLong("created_at_ms"),
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

    private static String boundedError(String message) {
        if (message == null || message.isBlank()) {
            return "UNKNOWN_ERROR";
        }
        String sanitized = message.replace('\r', ' ').replace('\n', ' ').trim();
        return sanitized.length() > 500 ? sanitized.substring(0, 500) : sanitized;
    }
}
