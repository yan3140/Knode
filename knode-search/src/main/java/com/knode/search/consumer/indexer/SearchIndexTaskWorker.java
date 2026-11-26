package com.knode.search.consumer.indexer;

import com.knode.search.cache.SearchCacheInvalidator;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "knode.search.consumer.indexer", name = "enabled", havingValue = "true")
public class SearchIndexTaskWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchIndexTaskWorker.class);

    private final SearchConsumerRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ArticleIndexWriter indexWriter;
    private final SearchCacheInvalidator cacheInvalidator;
    private final SearchConsumerProperties.IndexerProps indexerProps;
    private final String aliasName;
    private final String defaultPhysicalIndex;
    private final Clock clock;

    @Autowired
    public SearchIndexTaskWorker(
            SearchConsumerRepository repository,
            @Qualifier("searchConsumerTransactionTemplate") TransactionTemplate transactionTemplate,
            ArticleIndexWriter indexWriter,
            SearchCacheInvalidator cacheInvalidator,
            SearchConsumerProperties properties,
            SearchElasticsearchProperties esProperties,
            Clock clock) {
        this(
                repository,
                transactionTemplate,
                indexWriter,
                cacheInvalidator,
                properties,
                esProperties != null && StringUtils.hasText(esProperties.getIndex())
                        ? esProperties.getIndex().trim()
                        : "knode_local_articles_search",
                clock
        );
    }

    public SearchIndexTaskWorker(
            SearchConsumerRepository repository,
            TransactionTemplate transactionTemplate,
            ArticleIndexWriter indexWriter,
            SearchCacheInvalidator cacheInvalidator,
            SearchConsumerProperties properties,
            Clock clock) {
        this(repository, transactionTemplate, indexWriter, cacheInvalidator, properties, "default", "", clock);
    }

    public SearchIndexTaskWorker(
            SearchConsumerRepository repository,
            TransactionTemplate transactionTemplate,
            ArticleIndexWriter indexWriter,
            SearchCacheInvalidator cacheInvalidator,
            SearchConsumerProperties properties,
            String aliasName,
            Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.indexWriter = indexWriter;
        this.cacheInvalidator = cacheInvalidator;
        this.indexerProps = properties.getIndexer();
        this.aliasName = StringUtils.hasText(aliasName) ? aliasName.trim() : "knode_local_articles_search";
        this.defaultPhysicalIndex = this.aliasName;
        this.clock = clock;
    }


    private SearchIndexTaskWorker(
            SearchConsumerRepository repository,
            TransactionTemplate transactionTemplate,
            ArticleIndexWriter indexWriter,
            SearchCacheInvalidator cacheInvalidator,
            SearchConsumerProperties properties,
            String aliasName,
            String defaultPhysicalIndex,
            Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.indexWriter = indexWriter;
        this.cacheInvalidator = cacheInvalidator;
        this.indexerProps = properties.getIndexer();
        this.aliasName = aliasName;
        this.defaultPhysicalIndex = defaultPhysicalIndex;
        this.clock = clock;
    }

    public WorkerCycleSummary processOnce() {
        List<SearchIndexTaskRecord> claimed = claimBatch();
        return executeBatch(claimed, true);
    }

    public WorkerCycleSummary processCandidateGenerationOnce(long candidateGeneration, String candidatePhysicalIndex) {
        List<SearchIndexTaskRecord> claimed = claimCandidateBatch(candidateGeneration, candidatePhysicalIndex);
        return executeBatch(claimed, false);
    }

    private WorkerCycleSummary executeBatch(List<SearchIndexTaskRecord> claimed, boolean invalidateCache) {
        int applied = 0;
        int superseded = 0;
        int failed = 0;
        int staleRejected = 0;
        for (SearchIndexTaskRecord task : claimed) {
            TaskProcessOutcome outcome = executeClaimedTask(task, invalidateCache);
            switch (outcome) {
                case APPLIED_AND_CACHE_INVALIDATED, APPLIED_CACHE_PENDING -> applied++;
                case SUPERSEDED -> superseded++;
                case RETRY_OR_DEAD_SCHEDULED -> failed++;
                case STALE_REJECTED -> staleRejected++;
            }
        }
        CacheCompensationSummary cacheSummary = invalidateCache
                ? processPendingCacheInvalidations()
                : new CacheCompensationSummary(0, 0, 0);
        return new WorkerCycleSummary(claimed.size(), applied, superseded, failed, staleRejected, cacheSummary);
    }

    public List<SearchIndexTaskRecord> claimBatch() {
        int batchSize = Math.max(1, indexerProps.getBatchSize());
        long leaseDurationMs = Math.max(1, indexerProps.getLeaseSeconds()) * 1000L;
        List<SearchIndexTaskRecord> claimed = transactionTemplate.execute(
                status -> repository.claimDueOnlineTasksInCurrentTx(
                        aliasName, defaultPhysicalIndex, batchSize, leaseDurationMs, clock.millis()));
        return claimed == null ? List.of() : claimed;
    }

    public List<SearchIndexTaskRecord> claimCandidateBatch(long candidateGeneration, String candidatePhysicalIndex) {
        int batchSize = Math.max(1, indexerProps.getBatchSize());
        long leaseDurationMs = Math.max(1, indexerProps.getLeaseSeconds()) * 1000L;
        List<SearchIndexTaskRecord> claimed = transactionTemplate.execute(
                status -> repository.claimDueTasksForGenerationInCurrentTx(
                        candidateGeneration, candidatePhysicalIndex, batchSize, leaseDurationMs, clock.millis()));
        return claimed == null ? List.of() : claimed;
    }

    public TaskProcessOutcome executeClaimedTask(SearchIndexTaskRecord task) {
        return executeClaimedTask(task, true);
    }

    public TaskProcessOutcome executeClaimedTask(SearchIndexTaskRecord task, boolean allowCacheInvalidation) {
        // Network ES write MUST run outside any SQL transaction.
        IndexWriteResult writeResult = indexWriter.write(task);
        return completeClaimedTask(task, writeResult, allowCacheInvalidation);
    }

    public TaskProcessOutcome completeClaimedTask(SearchIndexTaskRecord task, IndexWriteResult writeResult) {
        return completeClaimedTask(task, writeResult, true);
    }

    public TaskProcessOutcome completeClaimedTask(
            SearchIndexTaskRecord task, IndexWriteResult writeResult, boolean allowCacheInvalidation) {
        return switch (writeResult.status()) {
            case APPLIED, IDEMPOTENT_APPLIED -> handleApplied(task, allowCacheInvalidation);
            case SUPERSEDED -> handleSuperseded(task);
            case CONFLICT_UNVERIFIED, FAILED -> handleFailure(task, writeResult.detail());
        };
    }

    private TaskProcessOutcome handleApplied(SearchIndexTaskRecord task, boolean allowCacheInvalidation) {
        boolean isTaskForActiveGeneration = isTaskActiveGeneration(task.indexGeneration());
        boolean needCacheInvalidation = allowCacheInvalidation
                && isTaskForActiveGeneration
                && cacheInvalidator.isCacheEnabled();
        Boolean marked = transactionTemplate.execute(status -> repository.markEsAppliedConditionally(
                task.indexGeneration(),
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
                    task.indexGeneration(), task.articleId(), task.targetProjectionRevision(), clock.millis()));
            return TaskProcessOutcome.APPLIED_AND_CACHE_INVALIDATED;
        } catch (Exception cacheEx) {
            String err = "CACHE_EPOCH_INCR_" + cacheEx.getClass().getSimpleName();
            LOGGER.warn("event=search_cache_invalidation_failed generation={} articleId={} revision={} error={}",
                    task.indexGeneration(), task.articleId(), task.targetProjectionRevision(), err);
            transactionTemplate.executeWithoutResult(status -> repository.markCacheInvalidationRetryConditionally(
                    task.indexGeneration(),
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

    private boolean isTaskActiveGeneration(long taskGeneration) {
        Optional<IndexGenerationState> state = repository.findGenerationState(aliasName);
        if (state.isEmpty()) {
            return taskGeneration == SearchConsumerRepository.DEFAULT_GENERATION;
        }
        return state.get().activeGeneration() == taskGeneration;
    }

    private TaskProcessOutcome handleSuperseded(SearchIndexTaskRecord task) {
        Boolean marked = transactionTemplate.execute(status -> repository.markSupersededConditionally(
                task.indexGeneration(),
                task.articleId(),
                task.targetProjectionRevision(),
                task.claimGeneration(),
                clock.millis()));
        return Boolean.TRUE.equals(marked) ? TaskProcessOutcome.SUPERSEDED : TaskProcessOutcome.STALE_REJECTED;
    }

    private TaskProcessOutcome handleFailure(SearchIndexTaskRecord task, String detail) {
        Boolean marked = transactionTemplate.execute(status -> repository.markRetryOrDeadConditionallyInCurrentTx(
                task.indexGeneration(),
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
        long nowMs = clock.millis();
        int batchSize = Math.max(1, indexerProps.getCacheCompensationBatchSize());
        List<SearchConsumerRepository.CacheCompensationCandidate> candidates =
                repository.findDueCacheInvalidations(batchSize, nowMs);
        List<SearchConsumerRepository.GenerationCacheCompensationCandidate> genCandidates =
                repository.findDueGenerationCacheInvalidations(nowMs);
        int totalCandidates = candidates.size() + genCandidates.size();
        if (totalCandidates == 0) {
            return new CacheCompensationSummary(0, 0, 0);
        }

        if (!cacheInvalidator.isCacheEnabled()) {
            int completed = 0;
            for (SearchConsumerRepository.CacheCompensationCandidate candidate : candidates) {
                Boolean done = transactionTemplate.execute(status ->
                        repository.markCacheInvalidationDoneConditionally(
                                candidate.indexGeneration(), candidate.articleId(), candidate.esAppliedRevision(), nowMs));
                if (Boolean.TRUE.equals(done)) {
                    completed++;
                }
            }
            for (SearchConsumerRepository.GenerationCacheCompensationCandidate gen : genCandidates) {
                transactionTemplate.executeWithoutResult(status ->
                        repository.markGenerationCacheInvalidationDone(gen.aliasName(), gen.activeGeneration(), nowMs));
                completed++;
            }
            return new CacheCompensationSummary(totalCandidates, completed, 0);
        }

        try {
            cacheInvalidator.advanceEpoch();
        } catch (Exception cacheEx) {
            String err = "CACHE_COMPENSATION_" + cacheEx.getClass().getSimpleName();
            LOGGER.warn("event=search_cache_compensation_failed batchSize={} error={}", totalCandidates, err);
            int rescheduled = 0;
            for (SearchConsumerRepository.CacheCompensationCandidate candidate : candidates) {
                Boolean updated = transactionTemplate.execute(status ->
                        repository.markCacheInvalidationRetryConditionally(
                                candidate.indexGeneration(),
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
            for (SearchConsumerRepository.GenerationCacheCompensationCandidate gen : genCandidates) {
                transactionTemplate.executeWithoutResult(status ->
                        repository.markGenerationCacheInvalidationRetry(
                                gen.aliasName(),
                                gen.activeGeneration(),
                                gen.attempts(),
                                err,
                                indexerProps.getCacheCompensationBaseDelayMs(),
                                indexerProps.getCacheCompensationMaxDelayMs(),
                                nowMs));
                rescheduled++;
            }
            return new CacheCompensationSummary(totalCandidates, 0, rescheduled);
        }

        int completed = 0;
        for (SearchConsumerRepository.CacheCompensationCandidate candidate : candidates) {
            Boolean done = transactionTemplate.execute(status ->
                    repository.markCacheInvalidationDoneConditionally(
                            candidate.indexGeneration(), candidate.articleId(), candidate.esAppliedRevision(), nowMs));
            if (Boolean.TRUE.equals(done)) {
                completed++;
            }
        }
        for (SearchConsumerRepository.GenerationCacheCompensationCandidate gen : genCandidates) {
            transactionTemplate.executeWithoutResult(status ->
                    repository.markGenerationCacheInvalidationDone(gen.aliasName(), gen.activeGeneration(), nowMs));
            completed++;
        }
        return new CacheCompensationSummary(totalCandidates, completed, 0);
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
