package com.knode.search.consumer.indexer;

import com.knode.search.cache.SearchCacheInvalidator;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "knode.search.consumer.indexer", name = "enabled", havingValue = "true")
public class SearchIndexTaskWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchIndexTaskWorker.class);

    private final SearchConsumerRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ArticleIndexWriter indexWriter;
    private final SearchCacheInvalidator cacheInvalidator;
    private final SearchConsumerProperties.IndexerProps indexerProps;
    private final Clock clock;

    public SearchIndexTaskWorker(
            SearchConsumerRepository repository,
            @Qualifier("searchConsumerTransactionTemplate") TransactionTemplate transactionTemplate,
            ArticleIndexWriter indexWriter,
            SearchCacheInvalidator cacheInvalidator,
            SearchConsumerProperties properties,
            Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.indexWriter = indexWriter;
        this.cacheInvalidator = cacheInvalidator;
        this.indexerProps = properties.getIndexer();
        this.clock = clock;
    }

    public WorkerCycleSummary processOnce() {
        List<SearchIndexTaskRecord> claimed = claimBatch();
        int applied = 0;
        int superseded = 0;
        int failed = 0;
        int staleRejected = 0;
        for (SearchIndexTaskRecord task : claimed) {
            TaskProcessOutcome outcome = executeClaimedTask(task);
            switch (outcome) {
                case APPLIED_AND_CACHE_INVALIDATED, APPLIED_CACHE_PENDING -> applied++;
                case SUPERSEDED -> superseded++;
                case RETRY_OR_DEAD_SCHEDULED -> failed++;
                case STALE_REJECTED -> staleRejected++;
            }
        }
        CacheCompensationSummary cacheSummary = processPendingCacheInvalidations();
        return new WorkerCycleSummary(claimed.size(), applied, superseded, failed, staleRejected, cacheSummary);
    }

    public List<SearchIndexTaskRecord> claimBatch() {
        int batchSize = Math.max(1, indexerProps.getBatchSize());
        long leaseDurationMs = Math.max(1, indexerProps.getLeaseSeconds()) * 1000L;
        List<SearchIndexTaskRecord> claimed = transactionTemplate.execute(
                status -> repository.claimDueTasksInCurrentTx(batchSize, leaseDurationMs, clock.millis()));
        return claimed == null ? List.of() : claimed;
    }

    public TaskProcessOutcome executeClaimedTask(SearchIndexTaskRecord task) {
        // Network ES write MUST run outside any SQL transaction.
        IndexWriteResult writeResult = indexWriter.write(task);
        return completeClaimedTask(task, writeResult);
    }

    public TaskProcessOutcome completeClaimedTask(SearchIndexTaskRecord task, IndexWriteResult writeResult) {
        return switch (writeResult.status()) {
            case APPLIED, IDEMPOTENT_APPLIED -> handleApplied(task);
            case SUPERSEDED -> handleSuperseded(task);
            case CONFLICT_UNVERIFIED, FAILED -> handleFailure(task, writeResult.detail());
        };
    }

    private TaskProcessOutcome handleApplied(SearchIndexTaskRecord task) {
        boolean needCacheInvalidation = cacheInvalidator.isCacheEnabled();
        Boolean marked = transactionTemplate.execute(status -> repository.markEsAppliedConditionally(
                task.articleId(),
                task.targetProjectionRevision(),
                task.claimGeneration(),
                needCacheInvalidation,
                clock.millis()));
        if (!Boolean.TRUE.equals(marked)) {
            return TaskProcessOutcome.STALE_REJECTED;
        }
        if (!needCacheInvalidation) {
            return TaskProcessOutcome.APPLIED_AND_CACHE_INVALIDATED;
        }

        // Bounded O(1) cache epoch advancement outside SQL transaction.
        try {
            cacheInvalidator.advanceEpoch();
            transactionTemplate.executeWithoutResult(status -> repository.markCacheInvalidationDoneConditionally(
                    task.articleId(), task.targetProjectionRevision(), clock.millis()));
            return TaskProcessOutcome.APPLIED_AND_CACHE_INVALIDATED;
        } catch (Exception cacheEx) {
            String err = "CACHE_EPOCH_INCR_" + cacheEx.getClass().getSimpleName();
            LOGGER.warn("event=search_cache_invalidation_failed articleId={} revision={} error={}",
                    task.articleId(), task.targetProjectionRevision(), err);
            transactionTemplate.executeWithoutResult(status -> repository.markCacheInvalidationRetryConditionally(
                    task.articleId(),
                    task.targetProjectionRevision(),
                    0,
                    err,
                    indexerProps.getCacheCompensationBaseDelayMs(),
                    indexerProps.getCacheCompensationMaxDelayMs(),
                    clock.millis()));
            return TaskProcessOutcome.APPLIED_CACHE_PENDING;
        }
    }

    private TaskProcessOutcome handleSuperseded(SearchIndexTaskRecord task) {
        Boolean marked = transactionTemplate.execute(status -> repository.markSupersededConditionally(
                task.articleId(),
                task.targetProjectionRevision(),
                task.claimGeneration(),
                clock.millis()));
        return Boolean.TRUE.equals(marked) ? TaskProcessOutcome.SUPERSEDED : TaskProcessOutcome.STALE_REJECTED;
    }

    private TaskProcessOutcome handleFailure(SearchIndexTaskRecord task, String detail) {
        Boolean marked = transactionTemplate.execute(status -> repository.markRetryOrDeadConditionallyInCurrentTx(
                task.articleId(),
                task.targetProjectionRevision(),
                task.claimGeneration(),
                detail,
                indexerProps.getBaseRetryDelayMs(),
                indexerProps.getMaxRetryDelayMs(),
                clock.millis()));
        return Boolean.TRUE.equals(marked)
                ? TaskProcessOutcome.RETRY_OR_DEAD_SCHEDULED
                : TaskProcessOutcome.STALE_REJECTED;
    }

    public CacheCompensationSummary processPendingCacheInvalidations() {
        int batchSize = Math.max(1, indexerProps.getCacheCompensationBatchSize());
        List<SearchConsumerRepository.CacheCompensationCandidate> candidates =
                repository.findDueCacheInvalidations(batchSize, clock.millis());
        if (candidates.isEmpty()) {
            return new CacheCompensationSummary(0, 0, 0);
        }

        if (!cacheInvalidator.isCacheEnabled()) {
            int completed = 0;
            long nowMs = clock.millis();
            for (SearchConsumerRepository.CacheCompensationCandidate candidate : candidates) {
                Boolean done = transactionTemplate.execute(status ->
                        repository.markCacheInvalidationDoneConditionally(
                                candidate.articleId(), candidate.esAppliedRevision(), nowMs));
                if (Boolean.TRUE.equals(done)) {
                    completed++;
                }
            }
            return new CacheCompensationSummary(candidates.size(), completed, 0);
        }

        try {
            cacheInvalidator.advanceEpoch();
        } catch (Exception cacheEx) {
            String err = "CACHE_COMPENSATION_" + cacheEx.getClass().getSimpleName();
            LOGGER.warn("event=search_cache_compensation_failed batchSize={} error={}", candidates.size(), err);
            int rescheduled = 0;
            long nowMs = clock.millis();
            for (SearchConsumerRepository.CacheCompensationCandidate candidate : candidates) {
                Boolean updated = transactionTemplate.execute(status ->
                        repository.markCacheInvalidationRetryConditionally(
                                candidate.articleId(),
                                candidate.esAppliedRevision(),
                                candidate.attempts(),
                                err,
                                indexerProps.getCacheCompensationBaseDelayMs(),
                                indexerProps.getCacheCompensationMaxDelayMs(),
                                nowMs));
                if (Boolean.TRUE.equals(updated)) {
                    rescheduled++;
                }
            }
            return new CacheCompensationSummary(candidates.size(), 0, rescheduled);
        }

        int completed = 0;
        long nowMs = clock.millis();
        for (SearchConsumerRepository.CacheCompensationCandidate candidate : candidates) {
            Boolean done = transactionTemplate.execute(status ->
                    repository.markCacheInvalidationDoneConditionally(
                            candidate.articleId(), candidate.esAppliedRevision(), nowMs));
            if (Boolean.TRUE.equals(done)) {
                completed++;
            }
        }
        return new CacheCompensationSummary(candidates.size(), completed, 0);
    }

    public enum TaskProcessOutcome {
        APPLIED_AND_CACHE_INVALIDATED,
        APPLIED_CACHE_PENDING,
        SUPERSEDED,
        RETRY_OR_DEAD_SCHEDULED,
        STALE_REJECTED
    }

    public record CacheCompensationSummary(int candidateCount, int completedCount, int failedCount) {
    }

    public record WorkerCycleSummary(
            int claimedCount,
            int appliedCount,
            int supersededCount,
            int failedCount,
            int staleRejectedCount,
            CacheCompensationSummary cacheCompensation
    ) {
    }
}
