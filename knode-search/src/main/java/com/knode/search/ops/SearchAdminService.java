package com.knode.search.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.auth.AdminPrincipal;
import com.knode.search.client.CanonicalIdParser;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.ArticleVersionBarrier;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexDocument;
import com.knode.search.consumer.persistence.SearchIndexOperation;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.QueryBudget;
import com.knode.search.model.SearchBusinessException;
import com.knode.search.model.SearchErrorCode;
import com.knode.search.rebuild.client.ArticleCurrentSnapshot;
import com.knode.search.rebuild.client.ContentSnapshotClient;
import com.knode.search.rebuild.client.SnapshotArticleItem;
import com.knode.search.rebuild.persistence.RebuildJobRecord;
import com.knode.search.rebuild.service.SearchRebuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SearchAdminService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchAdminService.class);

    private final SearchRebuildService rebuildService;
    private final SearchConsumerOpsService consumerOpsService;
    private final SearchConsumerRepository consumerRepository;
    private final TransactionTemplate transactionTemplate;
    private final SearchIndexTaskWorker indexTaskWorker;
    private final ContentSnapshotClient snapshotClient;
    private final SearchConsumerProperties consumerProperties;
    private final SearchOpsProperties opsProperties;
    private final SearchMetrics searchMetrics;
    private final ObjectMapper objectMapper;
    private final String aliasName;
    private final Clock clock;

    public SearchAdminService(
            ObjectProvider<SearchRebuildService> rebuildServiceProvider,
            SearchConsumerOpsService consumerOpsService,
            ObjectProvider<SearchConsumerRepository> consumerRepositoryProvider,
            @Qualifier("searchConsumerTransactionTemplate") ObjectProvider<TransactionTemplate> transactionTemplateProvider,
            ObjectProvider<SearchIndexTaskWorker> indexTaskWorkerProvider,
            ObjectProvider<ContentSnapshotClient> snapshotClientProvider,
            SearchConsumerProperties consumerProperties,
            SearchOpsProperties opsProperties,
            SearchElasticsearchProperties esProperties,
            SearchMetrics searchMetrics,
            ObjectMapper objectMapper,
            Clock clock) {
        this.rebuildService = rebuildServiceProvider.getIfAvailable();
        this.consumerOpsService = consumerOpsService;
        this.consumerRepository = consumerRepositoryProvider.getIfAvailable();
        this.transactionTemplate = transactionTemplateProvider.getIfAvailable();
        this.indexTaskWorker = indexTaskWorkerProvider.getIfAvailable();
        this.snapshotClient = snapshotClientProvider.getIfAvailable();
        this.consumerProperties = consumerProperties;
        this.opsProperties = opsProperties == null ? new SearchOpsProperties() : opsProperties;
        this.searchMetrics = searchMetrics;
        this.objectMapper = objectMapper;
        this.aliasName = esProperties != null && StringUtils.hasText(esProperties.getIndex())
                ? esProperties.getIndex().trim()
                : "knode_local_articles_search";
        this.clock = clock;
    }

    public Map<String, Object> triggerRebuild(AdminPrincipal admin) {
        if (rebuildService == null) {
            searchMetrics.recordAdminRebuild("error");
            throw new AdminDependencyUnavailableException("Search rebuild service is not enabled");
        }
        try {
            Optional<RebuildJobRecord> active = rebuildService.findActiveJob();
            boolean reused;
            RebuildJobRecord job;
            if (active.isPresent()) {
                job = active.get();
                reused = true;
            } else {
                try {
                    job = rebuildService.startRebuild();
                    reused = false;
                } catch (IllegalStateException raceOrCutover) {
                    Optional<RebuildJobRecord> racedActive = rebuildService.findActiveJob();
                    if (racedActive.isPresent()) {
                        job = racedActive.get();
                        reused = true;
                    } else {
                        throw raceOrCutover;
                    }
                }
            }
            searchMetrics.recordAdminRebuild(reused ? "reused" : "started");
            LOGGER.info("event=search_admin_rebuild adminUserId={} jobId={} phase={} reusedActiveJob={}",
                    admin.userId(), job.jobId(), job.phase(), reused);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobId", job.jobId());
            data.put("phase", job.phase().name());
            data.put("alias", job.aliasName());
            data.put("activeGeneration", Long.toString(job.sourceGeneration()));
            data.put("candidateGeneration", Long.toString(job.candidateGeneration()));
            data.put("candidatePhysicalIndex", job.candidatePhysicalIndex());
            data.put("reusedActiveJob", reused);
            data.put("indexed", Long.toString(job.snapshotItemsImported()));
            return data;
        } catch (Exception ex) {
            searchMetrics.recordAdminRebuild("error");
            LOGGER.warn("event=search_admin_rebuild_failed adminUserId={} errorType={}",
                    admin.userId(), ex.getClass().getSimpleName());
            throw new AdminDependencyUnavailableException("Unable to start or resume search rebuild", ex);
        }
    }

    public Map<String, Object> retryFailedWork(AdminPrincipal admin) {
        SearchOpsRepository.BatchRetryOutcome outcome = consumerOpsService.retryBatchForAdmin(admin.userId(), null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requeued", Long.toString(outcome.requeued()));
        data.put("skippedActiveLease", Long.toString(outcome.skippedActiveLease()));
        data.put("cacheCompensationsExpedited", Long.toString(outcome.cacheCompensationsExpedited()));
        return data;
    }

    public Map<String, Object> synchronizeArticle(AdminPrincipal admin, String rawArticleId, String requestId) {
        String canonicalId = CanonicalIdParser.normalizePositiveDecimalString(rawArticleId);
        if (canonicalId == null) {
            throw new SearchBusinessException(SearchErrorCode.PARAM_ERROR);
        }
        long articleId = Long.parseLong(canonicalId);
        if (consumerRepository == null || transactionTemplate == null || indexTaskWorker == null || snapshotClient == null) {
            searchMetrics.recordAdminSync("error");
            throw new AdminDependencyUnavailableException("Search consumer/indexer/snapshot client is not enabled");
        }

        // Step 1: Fetch authoritative current article snapshot via published 03 snapshots:batch (outside SQL tx).
        ArticleCurrentSnapshot currentSnapshot;
        try {
            QueryBudget budget = QueryBudget.start(Duration.ofMillis(Math.max(500L, opsProperties.getSyncTimeoutMs())));
            Map<Long, ArticleCurrentSnapshot> batch = snapshotClient.fetchCurrentSnapshotsBatch(
                    List.of(articleId), budget, StringUtils.hasText(requestId) ? requestId : "admin-sync-" + articleId);
            currentSnapshot = batch == null ? null : batch.get(articleId);
            if (currentSnapshot == null) {
                throw new IllegalStateException("Content snapshots:batch omitted requested articleId " + articleId);
            }
        } catch (Exception ex) {
            searchMetrics.recordAdminSync("error");
            LOGGER.warn("event=search_admin_sync_upstream_failed adminUserId={} articleId={} errorType={}",
                    admin.userId(), articleId, ex.getClass().getSimpleName());
            throw new AdminDependencyUnavailableException("Upstream content snapshot service unavailable", ex);
        }

        // Step 2: Apply through local version/delete barrier and enqueue reliable index task inside READ COMMITTED tx.
        SyncPlan plan;
        try {
            plan = transactionTemplate.execute(status -> applyCurrentSnapshotInTx(articleId, currentSnapshot));
        } catch (AdminDependencyUnavailableException ex) {
            searchMetrics.recordAdminSync("error");
            throw ex;
        } catch (Exception ex) {
            searchMetrics.recordAdminSync("error");
            LOGGER.warn("event=search_admin_sync_db_failed adminUserId={} articleId={} errorType={}",
                    admin.userId(), articleId, ex.getClass().getSimpleName());
            throw new AdminDependencyUnavailableException("Failed to persist single-article sync state", ex);
        }

        if (!plan.taskEnqueued()) {
            String metricOutcome = "STALE_BLOCKED_BY_BARRIER".equals(plan.action()) ? "stale_blocked" : "noop";
            searchMetrics.recordAdminSync(metricOutcome);
            LOGGER.info("event=search_admin_sync_completed adminUserId={} articleId={} action={} visibility={}",
                    admin.userId(), articleId, plan.action(), currentSnapshot.visibility());
            return buildSyncResponse(canonicalId, currentSnapshot, plan, false);
        }

        // Step 3: Immediately execute the enqueued index task through the standard lease + external-version worker.
        Optional<SearchIndexTaskWorker.TaskProcessOutcome> workerOutcome;
        try {
            workerOutcome = indexTaskWorker.processSingleArticleImmediately(articleId);
        } catch (Exception ex) {
            searchMetrics.recordAdminSync("error");
            LOGGER.warn("event=search_admin_sync_es_failed adminUserId={} articleId={} errorType={}",
                    admin.userId(), articleId, ex.getClass().getSimpleName());
            throw new AdminDependencyUnavailableException("Elasticsearch synchronization failed; task queued for retry", ex);
        }

        if (workerOutcome.isPresent()
                && workerOutcome.get() == SearchIndexTaskWorker.TaskProcessOutcome.RETRY_OR_DEAD_SCHEDULED) {
            searchMetrics.recordAdminSync("error");
            throw new AdminDependencyUnavailableException("Elasticsearch synchronization failed; task queued for retry");
        }
        boolean cacheInvalidated = workerOutcome.isPresent()
                && workerOutcome.get() == SearchIndexTaskWorker.TaskProcessOutcome.APPLIED_AND_CACHE_INVALIDATED;
        searchMetrics.recordAdminSync("applied");
        LOGGER.info("event=search_admin_sync_completed adminUserId={} articleId={} action={} visibility={} workerOutcome={}",
                admin.userId(), articleId, plan.action(), currentSnapshot.visibility(),
                workerOutcome.map(Enum::name).orElse("QUEUED_FOR_WORKER"));
        return buildSyncResponse(canonicalId, currentSnapshot, plan, cacheInvalidated);
    }

    private SyncPlan applyCurrentSnapshotInTx(long articleId, ArticleCurrentSnapshot authoritative) {
        long nowMs = clock.millis();
        IndexGenerationState gen = consumerRepository.ensureAndLockGeneration(aliasName, aliasName, nowMs);
        if (!gen.isNormal()) {
            throw new AdminDependencyUnavailableException(
                    "Search index cutover is in progress (" + gen.cutoverState() + ")");
        }
        long activeGen = gen.activeGeneration();
        ArticleVersionBarrier barrier = consumerRepository.ensureAndLockVersionBarrier(articleId, nowMs);
        ArticleProjectionRecord current = consumerRepository.ensureAndLockProjection(activeGen, articleId, nowMs);
        if (barrier.maxContentRevision() > current.contentRevision()
                || (barrier.maxContentRevision() == current.contentRevision()
                && barrier.maxContentRevision() > 0L
                && SearchConsumerRepository.visibilityRank(barrier.lastVisibility())
                > SearchConsumerRepository.visibilityRank(current.visibility()))) {
            barrier = consumerRepository.resolveEffectiveVersionBarrier(activeGen, articleId, barrier, true, nowMs);
        }
        long effectiveFloor = Math.max(current.contentRevision(), barrier.maxContentRevision());

        if (authoritative.isReadablePublic()) {
            SnapshotArticleItem item = authoritative.snapshot();
            long rev = item.sourceRevision();
            boolean blockedByHiddenBarrier = rev == effectiveFloor
                    && (barrier.lastVisibility() == ArticleVisibility.DELETED
                    || barrier.lastVisibility() == ArticleVisibility.WITHDRAWN
                    || current.visibility() == ArticleVisibility.DELETED
                    || current.visibility() == ArticleVisibility.WITHDRAWN);
            if (rev < effectiveFloor || blockedByHiddenBarrier) {
                return new SyncPlan("STALE_BLOCKED_BY_BARRIER", current.projectionRevision(), false);
            }

            long mergedCounterRev;
            long mergedViewCount;
            if (item.counterRevision() > current.counterRevision()) {
                mergedCounterRev = item.counterRevision();
                mergedViewCount = item.viewCount();
            } else if (!current.hasContent() && current.counterRevision() == 0L && item.counterRevision() == 0L) {
                mergedCounterRev = 0L;
                mergedViewCount = item.viewCount();
            } else {
                mergedCounterRev = current.counterRevision();
                mergedViewCount = current.viewCount();
            }

            long nextProjRev = current.projectionRevision() + 1L;
            ArticleProjectionRecord updated = new ArticleProjectionRecord(
                    articleId,
                    true,
                    rev,
                    ArticleVisibility.PUBLIC,
                    mergedCounterRev,
                    mergedViewCount,
                    nextProjRev,
                    item.title(),
                    item.summary(),
                    item.content(),
                    item.categoryId(),
                    item.categoryName(),
                    item.tagNames(),
                    item.thumbnail(),
                    item.isTop(),
                    item.createTimeMs(),
                    nowMs
            );
            consumerRepository.updateProjection(activeGen, updated);
            consumerRepository.advanceVersionBarrierIfHigher(articleId, rev, ArticleVisibility.PUBLIC, nowMs);

            SearchIndexDocument doc = SearchIndexDocument.upsert(
                    updated.articleId(),
                    updated.contentRevision(),
                    updated.title(),
                    updated.summary(),
                    updated.content(),
                    updated.categoryId(),
                    updated.categoryName(),
                    updated.tagNames(),
                    updated.thumbnail(),
                    updated.viewCount(),
                    updated.createTimeMs()
            );
            consumerRepository.upsertPendingIndexTask(
                    activeGen,
                    gen.activePhysicalIndex(),
                    articleId,
                    nextProjRev,
                    SearchIndexOperation.UPSERT,
                    serializeDocument(doc),
                    consumerProperties.getIndexer().getMaxAttempts(),
                    nowMs
            );
            return new SyncPlan("UPSERT_SYNCED", nextProjRev, true);
        }

        if (authoritative.isMissing()) {
            Optional<SearchIndexTaskRecord> existingTask = consumerRepository.findIndexTask(activeGen, articleId);
            boolean needsTombstone = (current.hasContent() && current.visibility() == ArticleVisibility.PUBLIC)
                    || (existingTask.isPresent() && existingTask.get().operation() == SearchIndexOperation.UPSERT);
            if (!needsTombstone) {
                return new SyncPlan("NOOP_MISSING", current.projectionRevision(), false);
            }
            long nextProjRev = current.projectionRevision() + 1L;
            long tombstoneRev = Math.max(effectiveFloor, 0L);
            ArticleProjectionRecord updated = new ArticleProjectionRecord(
                    articleId,
                    true,
                    tombstoneRev,
                    ArticleVisibility.DELETED,
                    current.counterRevision(),
                    current.viewCount(),
                    nextProjRev,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    current.createTimeMs(),
                    nowMs
            );
            consumerRepository.updateProjection(activeGen, updated);
            consumerRepository.advanceVersionBarrierIfHigher(articleId, tombstoneRev, ArticleVisibility.DELETED, nowMs);
            SearchIndexDocument tombstone = SearchIndexDocument.tombstone(articleId, tombstoneRev);
            consumerRepository.upsertPendingIndexTask(
                    activeGen,
                    gen.activePhysicalIndex(),
                    articleId,
                    nextProjRev,
                    SearchIndexOperation.TOMBSTONE,
                    serializeDocument(tombstone),
                    consumerProperties.getIndexer().getMaxAttempts(),
                    nowMs
            );
            return new SyncPlan("TOMBSTONE_SYNCED", nextProjRev, true);
        }

        // WITHDRAWN, DELETED, or DRAFT (all non-public with authoritative sourceRevision)
        long rev = authoritative.sourceRevision();
        if (rev < effectiveFloor) {
            return new SyncPlan("STALE_BLOCKED_BY_BARRIER", current.projectionRevision(), false);
        }
        ArticleVisibility hiddenVis = "DELETED".equals(authoritative.visibility())
                ? ArticleVisibility.DELETED
                : ArticleVisibility.WITHDRAWN;
        long nextProjRev = current.projectionRevision() + 1L;
        ArticleProjectionRecord updated = new ArticleProjectionRecord(
                articleId,
                true,
                rev,
                hiddenVis,
                current.counterRevision(),
                current.viewCount(),
                nextProjRev,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                current.createTimeMs(),
                nowMs
        );
        consumerRepository.updateProjection(activeGen, updated);
        consumerRepository.advanceVersionBarrierIfHigher(articleId, rev, hiddenVis, nowMs);

        SearchIndexDocument tombstone = SearchIndexDocument.tombstone(articleId, rev);
        consumerRepository.upsertPendingIndexTask(
                activeGen,
                gen.activePhysicalIndex(),
                articleId,
                nextProjRev,
                SearchIndexOperation.TOMBSTONE,
                serializeDocument(tombstone),
                consumerProperties.getIndexer().getMaxAttempts(),
                nowMs
        );
        return new SyncPlan("TOMBSTONE_SYNCED", nextProjRev, true);
    }

    private static Map<String, Object> buildSyncResponse(
            String articleId, ArticleCurrentSnapshot snapshot, SyncPlan plan, boolean cacheInvalidated) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("articleId", articleId);
        data.put("visibility", snapshot.visibility());
        data.put("sourceRevision", snapshot.sourceRevision() == null ? null : Long.toString(snapshot.sourceRevision()));
        data.put("projectionRevision", Long.toString(plan.projectionRevision()));
        data.put("action", plan.action());
        data.put("cacheInvalidated", cacheInvalidated);
        return data;
    }

    private String serializeDocument(SearchIndexDocument doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize SearchIndexDocument", ex);
        }
    }

    private record SyncPlan(String action, long projectionRevision, boolean taskEnqueued) {
    }
}
