package com.knode.search.rebuild.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.cache.SearchCacheInvalidator;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.event.ContentEventParser;
import com.knode.search.consumer.event.ParsedContentEvent;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexDocument;
import com.knode.search.consumer.persistence.SearchIndexOperation;
import com.knode.search.model.QueryBudget;
import com.knode.search.rebuild.client.ChangeCommitBatch;
import com.knode.search.rebuild.client.ChangeFeedPage;
import com.knode.search.rebuild.client.ContentSnapshotClient;
import com.knode.search.rebuild.client.ContentSnapshotException;
import com.knode.search.rebuild.client.SnapshotArticleItem;
import com.knode.search.rebuild.client.SnapshotHandle;
import com.knode.search.rebuild.client.SnapshotPage;
import com.knode.search.rebuild.config.SearchRebuildProperties;
import com.knode.search.rebuild.es.SearchIndexAdminClient;
import com.knode.search.rebuild.es.SearchVisibleDoc;
import com.knode.search.rebuild.persistence.RebuildJobRecord;
import com.knode.search.rebuild.persistence.RebuildPhase;
import com.knode.search.rebuild.persistence.SearchRebuildRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "knode.search.rebuild", name = "enabled", havingValue = "true")
public class SearchRebuildService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchRebuildService.class);
    private static final List<String> TOMBSTONE_FORBIDDEN_FIELDS = List.of(
            "title", "summary", "content", "categoryName", "tagNames", "thumbnail"
    );

    private final SearchRebuildRepository rebuildRepository;
    private final SearchConsumerRepository consumerRepository;
    private final TransactionTemplate transactionTemplate;
    private final ContentSnapshotClient snapshotClient;
    private final ContentEventParser eventParser;
    private final SearchIndexTaskWorker indexTaskWorker;
    private final SearchIndexAdminClient indexAdminClient;
    private final SearchCacheInvalidator cacheInvalidator;
    private final SearchConsumerProperties consumerProperties;
    private final SearchRebuildProperties rebuildProperties;
    private final String aliasName;
    private final String instanceId;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SearchRebuildService(
            SearchRebuildRepository rebuildRepository,
            SearchConsumerRepository consumerRepository,
            @Qualifier("searchConsumerTransactionTemplate") TransactionTemplate transactionTemplate,
            ContentSnapshotClient snapshotClient,
            ContentEventParser eventParser,
            SearchIndexTaskWorker indexTaskWorker,
            SearchIndexAdminClient indexAdminClient,
            SearchCacheInvalidator cacheInvalidator,
            SearchConsumerProperties consumerProperties,
            SearchRebuildProperties rebuildProperties,
            SearchElasticsearchProperties esProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this(
                rebuildRepository,
                consumerRepository,
                transactionTemplate,
                snapshotClient,
                eventParser,
                indexTaskWorker,
                indexAdminClient,
                cacheInvalidator,
                consumerProperties,
                rebuildProperties,
                esProperties.getIndex().trim(),
                resolveInstanceId(rebuildProperties.getInstanceId()),
                objectMapper,
                clock
        );
    }

    public SearchRebuildService(
            SearchRebuildRepository rebuildRepository,
            SearchConsumerRepository consumerRepository,
            TransactionTemplate transactionTemplate,
            ContentSnapshotClient snapshotClient,
            ContentEventParser eventParser,
            SearchIndexTaskWorker indexTaskWorker,
            SearchIndexAdminClient indexAdminClient,
            SearchCacheInvalidator cacheInvalidator,
            SearchConsumerProperties consumerProperties,
            SearchRebuildProperties rebuildProperties,
            String aliasName,
            String instanceId,
            ObjectMapper objectMapper,
            Clock clock) {
        if (!StringUtils.hasText(aliasName)) {
            throw new IllegalStateException("knode.search.elasticsearch.index (alias) must be configured");
        }
        this.rebuildRepository = rebuildRepository;
        this.consumerRepository = consumerRepository;
        this.transactionTemplate = transactionTemplate;
        this.snapshotClient = snapshotClient;
        this.eventParser = eventParser;
        this.indexTaskWorker = indexTaskWorker;
        this.indexAdminClient = indexAdminClient;
        this.cacheInvalidator = cacheInvalidator;
        this.consumerProperties = consumerProperties;
        this.rebuildProperties = rebuildProperties;
        this.aliasName = aliasName.trim();
        this.instanceId = StringUtils.hasText(instanceId) ? instanceId.trim() : resolveInstanceId("");
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String aliasName() {
        return aliasName;
    }

    public String instanceId() {
        return instanceId;
    }

    public Optional<RebuildJobRecord> findJob(String jobId) {
        return rebuildRepository.findJob(jobId);
    }

    public Optional<RebuildJobRecord> findActiveJob() {
        return rebuildRepository.findActiveJob(aliasName);
    }

    public Optional<IndexGenerationState> findGenerationState() {
        return consumerRepository.findGenerationState(aliasName);
    }

    public RebuildJobRecord startRebuild() {
        return startRebuild(null);
    }

    public RebuildJobRecord startRebuild(String requestedClientRequestId) {
        String jobId = generateJobId();
        String clientRequestId = StringUtils.hasText(requestedClientRequestId)
                ? requestedClientRequestId.trim()
                : generateClientRequestId();
        String defaultPhysical = resolveInitialPhysicalIndex();
        long leaseDurationMs = Math.max(5, rebuildProperties.getJobLeaseSeconds()) * 1000L;
        return transactionTemplate.execute(status -> rebuildRepository.startJobInCurrentTx(
                jobId, aliasName, defaultPhysical, clientRequestId, instanceId, leaseDurationMs, clock.millis()));
    }

    public StepOutcome advanceActiveJob() {
        Optional<RebuildJobRecord> active = rebuildRepository.findActiveJob(aliasName);
        if (active.isEmpty()) {
            return new StepOutcome(StepStatus.NO_ACTIVE_JOB, null, null);
        }
        return advanceJob(active.get().jobId());
    }

    public StepOutcome advanceJob(String jobId) {
        long leaseDurationMs = Math.max(5, rebuildProperties.getJobLeaseSeconds()) * 1000L;
        Optional<RebuildJobRecord> leased = transactionTemplate.execute(status ->
                rebuildRepository.acquireOrRenewLeaseInCurrentTx(jobId, instanceId, leaseDurationMs, clock.millis()));
        if (leased == null || leased.isEmpty()) {
            Optional<RebuildJobRecord> existing = rebuildRepository.findJob(jobId);
            if (existing.isEmpty()) {
                return new StepOutcome(StepStatus.NO_ACTIVE_JOB, null, "JOB_NOT_FOUND");
            }
            return new StepOutcome(StepStatus.LEASE_HELD_BY_OTHER, existing.get(), "LEASE_HELD_BY_" + existing.get().leaseOwner());
        }

        RebuildJobRecord job = leased.get();
        if (job.phase().isTerminal()) {
            return new StepOutcome(StepStatus.ALREADY_TERMINAL, job, null);
        }

        return switch (job.phase()) {
            case INIT -> stepCreateSnapshot(job);
            case SNAPSHOT_IMPORTING -> stepImportSnapshotPage(job);
            case INCREMENTAL_CATCHUP -> stepCatchupChanges(job, false);
            case CANDIDATE_INDEXING -> stepDrainCandidateIndexing(job);
            case CUTOVER_PAUSING -> stepEnterCutoverPause(job);
            case FINAL_CATCHUP -> stepCatchupChanges(job, true);
            case VERIFYING_CANDIDATE -> stepVerifyCandidate(job);
            case SWITCHING_ALIAS -> stepSwitchAliasOrReconcile(job);
            case CACHE_INVALIDATING -> stepInvalidateCache(job);
            default -> new StepOutcome(StepStatus.ALREADY_TERMINAL, job, null);
        };
    }

    public RebuildJobRecord runUntilTerminalOrLimit(String initialJobId, int maxSteps) {
        String currentJobId = initialJobId;
        int limit = Math.max(1, maxSteps);
        for (int i = 0; i < limit; i++) {
            StepOutcome outcome = advanceJob(currentJobId);
            if (outcome.job() == null) {
                break;
            }
            currentJobId = outcome.job().jobId();
            if (outcome.job().phase().isTerminal()
                    && outcome.status() != StepStatus.EXPIRED_410_RESTARTED) {
                return outcome.job();
            }
        }
        return rebuildRepository.findJob(currentJobId).orElseThrow();
    }

    private StepOutcome stepCreateSnapshot(RebuildJobRecord job) {
        try {
            indexAdminClient.ensurePhysicalIndex(job.candidatePhysicalIndex());
        } catch (Exception ex) {
            String err = "CANDIDATE_INDEX_PREPARE_FAILED:" + ex.getMessage();
            recordTransientError(job.jobId(), err);
            return new StepOutcome(StepStatus.RETRYABLE_ERROR, reload(job.jobId()), err);
        }

        QueryBudget budget = QueryBudget.start(Duration.ofMillis(rebuildProperties.getRequestTimeoutMs()));
        try {
            SnapshotHandle handle = snapshotClient.createSearchRebuildSnapshot(
                    job.clientRequestId(), budget, "rb-create-" + job.jobId());
            transactionTemplate.executeWithoutResult(status -> rebuildRepository.updateSnapshotHandleInCurrentTx(
                    job.jobId(),
                    handle.snapshotId(),
                    handle.incrementalCursor(),
                    handle.expiresAt().toEpochMilli(),
                    clock.millis()
            ));
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (ContentSnapshotException snapEx) {
            return handleContentSnapshotException(job, snapEx);
        }
    }

    private StepOutcome stepImportSnapshotPage(RebuildJobRecord job) {
        QueryBudget budget = QueryBudget.start(Duration.ofMillis(rebuildProperties.getRequestTimeoutMs()));
        SnapshotPage page;
        try {
            page = snapshotClient.fetchSnapshotPage(
                    job.snapshotId(),
                    job.snapshotPageCursor(),
                    rebuildProperties.getSnapshotPageSize(),
                    budget,
                    "rb-page-" + job.jobId() + "-" + job.snapshotPagesImported()
            );
        } catch (ContentSnapshotException snapEx) {
            return handleContentSnapshotException(job, snapEx);
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                for (SnapshotArticleItem item : page.items()) {
                    applySnapshotItemInTx(job, item, nowMs);
                }
                rebuildRepository.updateSnapshotPageCheckpointInCurrentTx(
                        job.jobId(),
                        page.nextCursor(),
                        page.done(),
                        page.items().size(),
                        nowMs
                );
            });
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (Exception dbEx) {
            String err = "SNAPSHOT_PAGE_PERSIST_FAILED:" + dbEx.getMessage();
            LOGGER.warn("event=search_rebuild_snapshot_page_failed jobId={} error={}", job.jobId(), err);
            recordTransientError(job.jobId(), err);
            return new StepOutcome(StepStatus.RETRYABLE_ERROR, reload(job.jobId()), err);
        }
    }

    private StepOutcome stepCatchupChanges(RebuildJobRecord job, boolean finalCatchup) {
        QueryBudget budget = QueryBudget.start(Duration.ofMillis(rebuildProperties.getRequestTimeoutMs()));
        ChangeFeedPage page;
        try {
            page = snapshotClient.fetchArticleChanges(
                    job.incrementalCursor(),
                    rebuildProperties.getChangeBatchLimit(),
                    budget,
                    "rb-changes-" + job.jobId() + "-" + job.lastCommitSequence()
            );
        } catch (ContentSnapshotException snapEx) {
            return handleContentSnapshotException(job, snapEx);
        }

        // Pre-parse all events before opening SQL transaction so bad protocol never partially commits.
        List<ParsedCommit> parsedCommits = new ArrayList<>(page.commits().size());
        int totalEvents = 0;
        long highestSeq = job.lastCommitSequence();
        for (ChangeCommitBatch batch : page.commits()) {
            List<ParsedContentEvent> events = new ArrayList<>(batch.rawEventJsons().size());
            for (String rawJson : batch.rawEventJsons()) {
                ContentEventParser.ParseResult parsed = eventParser.parseJson(rawJson);
                if (!parsed.isSuccess()) {
                    String err = "CHANGE_EVENT_PROTOCOL_ERROR:" + parsed.failure().errorCode()
                            + ":" + parsed.failure().errorMessage();
                    recordTransientError(job.jobId(), err);
                    return new StepOutcome(StepStatus.RETRYABLE_ERROR, reload(job.jobId()), err);
                }
                events.add(parsed.event());
                totalEvents++;
            }
            if (batch.commitSequence() > highestSeq) {
                highestSeq = batch.commitSequence();
            }
            parsedCommits.add(new ParsedCommit(batch.commitSequence(), events));
        }

        final long finalHighestSeq = highestSeq;
        final int finalTotalEvents = totalEvents;
        RebuildPhase nextPhase;
        if (finalCatchup) {
            nextPhase = page.done() ? RebuildPhase.VERIFYING_CANDIDATE : RebuildPhase.FINAL_CATCHUP;
        } else {
            nextPhase = page.done() ? RebuildPhase.CANDIDATE_INDEXING : RebuildPhase.INCREMENTAL_CATCHUP;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                for (ParsedCommit commit : parsedCommits) {
                    for (ParsedContentEvent event : commit.events()) {
                        applyChangeEventInTx(job, commit.commitSequence(), event, nowMs);
                    }
                }
                rebuildRepository.updateIncrementalCheckpointInCurrentTx(
                        job.jobId(),
                        page.nextCursor(),
                        page.cursorExpiresAt().toEpochMilli(),
                        finalHighestSeq,
                        parsedCommits.size(),
                        finalTotalEvents,
                        nextPhase,
                        nowMs
                );
            });
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (Exception dbEx) {
            String err = "CHANGE_BATCH_PERSIST_FAILED:" + dbEx.getMessage();
            LOGGER.warn("event=search_rebuild_change_batch_failed jobId={} error={}", job.jobId(), err);
            recordTransientError(job.jobId(), err);
            return new StepOutcome(StepStatus.RETRYABLE_ERROR, reload(job.jobId()), err);
        }
    }

    private StepOutcome stepDrainCandidateIndexing(RebuildJobRecord job) {
        drainCandidateTasks(job);
        long dead = consumerRepository.countDeadTasks(job.candidateGeneration());
        if (dead > 0L) {
            String err = "CANDIDATE_INDEX_DEAD_TASKS:" + dead;
            transactionTemplate.executeWithoutResult(status ->
                    rebuildRepository.markJobFailedInCurrentTx(job.jobId(), job.aliasName(), err, clock.millis()));
            return new StepOutcome(StepStatus.FAILED, reload(job.jobId()), err);
        }
        long incomplete = consumerRepository.countIncompleteTasks(job.candidateGeneration());
        if (incomplete == 0L) {
            transactionTemplate.executeWithoutResult(status ->
                    rebuildRepository.updatePhaseInCurrentTx(
                            job.jobId(), RebuildPhase.CUTOVER_PAUSING, null, clock.millis()));
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        }
        return new StepOutcome(StepStatus.WAITING_RETRY, reload(job.jobId()), "INCOMPLETE_TASKS:" + incomplete);
    }

    private StepOutcome stepEnterCutoverPause(RebuildJobRecord job) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    rebuildRepository.enterCutoverPauseInCurrentTx(job, clock.millis()));
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (Exception ex) {
            String err = "CUTOVER_PAUSE_FAILED:" + ex.getMessage();
            recordTransientError(job.jobId(), err);
            return new StepOutcome(StepStatus.RETRYABLE_ERROR, reload(job.jobId()), err);
        }
    }

    private StepOutcome stepVerifyCandidate(RebuildJobRecord job) {
        // First ensure any candidate index tasks added by FINAL_CATCHUP are drained to candidate ES index.
        drainCandidateTasks(job);
        long dead = consumerRepository.countDeadTasks(job.candidateGeneration());
        if (dead > 0L) {
            String err = "FINAL_CANDIDATE_DEAD_TASKS:" + dead;
            transactionTemplate.executeWithoutResult(status ->
                    rebuildRepository.markJobFailedInCurrentTx(job.jobId(), job.aliasName(), err, clock.millis()));
            return new StepOutcome(StepStatus.FAILED, reload(job.jobId()), err);
        }
        long incomplete = consumerRepository.countIncompleteTasks(job.candidateGeneration());
        if (incomplete > 0L) {
            return new StepOutcome(StepStatus.WAITING_RETRY, reload(job.jobId()), "FINAL_INCOMPLETE_TASKS:" + incomplete);
        }

        // Refresh candidate index and verify count + sampled public docs + sampled tombstones strictly on search path.
        try {
            indexAdminClient.refreshIndex(job.candidatePhysicalIndex());
            String mismatch = verifyCandidateIndexOnSearchPath(job);
            if (mismatch != null) {
                LOGGER.warn("event=search_rebuild_verification_failed jobId={} candidateIndex={} reason={}",
                        job.jobId(), job.candidatePhysicalIndex(), mismatch);
                transactionTemplate.executeWithoutResult(status ->
                        rebuildRepository.revertConfirmedPreSwitchFailureInCurrentTx(
                                job, true, "CANDIDATE_VERIFY_FAILED:" + mismatch, clock.millis()));
                return new StepOutcome(StepStatus.FAILED, reload(job.jobId()), mismatch);
            }
        } catch (Exception ex) {
            String err = "CANDIDATE_VERIFY_ERROR:" + ex.getMessage();
            recordTransientError(job.jobId(), err);
            return new StepOutcome(StepStatus.RETRYABLE_ERROR, reload(job.jobId()), err);
        }

        // Persist SWITCHING_ALIAS intent BEFORE issuing ES _aliases!
        transactionTemplate.executeWithoutResult(status ->
                rebuildRepository.enterSwitchingAliasInCurrentTx(job, clock.millis()));
        return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
    }

    private StepOutcome stepSwitchAliasOrReconcile(RebuildJobRecord job) {
        Set<String> currentTargets;
        try {
            currentTargets = indexAdminClient.getAliasTargets(job.aliasName());
        } catch (Exception ex) {
            String err = "ALIAS_RECONCILE_READ_FAILED:" + ex.getMessage();
            recordTransientError(job.jobId(), err);
            return new StepOutcome(StepStatus.ALIAS_SWITCH_AMBIGUOUS, reload(job.jobId()), err);
        }

        // Case 1: ES alias ALREADY points solely to candidatePhysicalIndex -> reconcile forward!
        if (currentTargets.equals(Set.of(job.candidatePhysicalIndex()))) {
            LOGGER.info("event=search_rebuild_alias_reconciled_forward jobId={} alias={} target={}",
                    job.jobId(), job.aliasName(), job.candidatePhysicalIndex());
            return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        }

        // Case 2: Confirmed pre-switch state (either empty alias on bootstrap or pointing to sourcePhysicalIndex).
        boolean validPreSwitch = currentTargets.isEmpty()
                || currentTargets.equals(Set.of(job.sourcePhysicalIndex()));
        if (!validPreSwitch) {
            String err = "UNEXPECTED_ALIAS_TARGETS:" + currentTargets;
            recordTransientError(job.jobId(), err);
            return new StepOutcome(StepStatus.ALIAS_SWITCH_AMBIGUOUS, reload(job.jobId()), err);
        }

        // Issue ONE atomic ES _aliases switch (retaining old physical index).
        try {
            indexAdminClient.switchAliasAtomically(job.aliasName(), currentTargets, job.candidatePhysicalIndex());
        } catch (Exception switchEx) {
            String switchErr = "ALIAS_SWITCH_FAILED:" + switchEx.getMessage();
            LOGGER.warn("event=search_rebuild_alias_switch_error jobId={} alias={} error={}",
                    job.jobId(), job.aliasName(), switchErr);

            // Immediately reconcile against authoritative ES alias state to distinguish pre-switch failure vs lost response!
            Set<String> postTargets;
            try {
                postTargets = indexAdminClient.getAliasTargets(job.aliasName());
            } catch (Exception postReadEx) {
                // Ambiguous: cannot verify whether ES applied the switch -> keep SWITCHING_ALIAS for restart reconciliation!
                String ambErr = "ALIAS_SWITCH_AMBIGUOUS:" + switchEx.getMessage() + ";postCheck=" + postReadEx.getMessage();
                recordTransientError(job.jobId(), ambErr);
                return new StepOutcome(StepStatus.ALIAS_SWITCH_AMBIGUOUS, reload(job.jobId()), ambErr);
            }

            if (postTargets.equals(Set.of(job.candidatePhysicalIndex()))) {
                // ES actually applied the atomic switch before response failed -> reconcile forward!
                LOGGER.info("event=search_rebuild_alias_reconciled_after_lost_response jobId={} alias={} target={}",
                        job.jobId(), job.aliasName(), job.candidatePhysicalIndex());
                return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
            }

            if (postTargets.isEmpty() || postTargets.equals(Set.of(job.sourcePhysicalIndex()))) {
                // Confirmed pre-switch failure: old alias is intact -> revert cutover pause so old generation keeps serving!
                boolean exhausted = job.switchAttempts() >= rebuildProperties.getMaxSwitchAttempts();
                transactionTemplate.executeWithoutResult(status ->
                        rebuildRepository.revertConfirmedPreSwitchFailureInCurrentTx(
                                job, exhausted, switchErr, clock.millis()));
                return new StepOutcome(
                        exhausted ? StepStatus.FAILED : StepStatus.PRE_SWITCH_FAILED_REVERTED,
                        reload(job.jobId()),
                        switchErr
                );
            }

            String weirdErr = "ALIAS_SWITCH_UNEXPECTED_POST_TARGETS:" + postTargets;
            recordTransientError(job.jobId(), weirdErr);
            return new StepOutcome(StepStatus.ALIAS_SWITCH_AMBIGUOUS, reload(job.jobId()), weirdErr);
        }

        // Hook point for subclasses/tests to simulate crash between ES _aliases success and local SQL activation!
        afterAtomicAliasSwitchBeforeLocalCommit(job);

        return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_SWITCHED_AND_ACTIVATED);
    }

    private StepOutcome activateCandidateAndAttemptCacheInvalidation(
            RebuildJobRecord job, StepStatus activationStatus) {
        boolean cacheEnabled = cacheInvalidator.isCacheEnabled();
        transactionTemplate.executeWithoutResult(status ->
                rebuildRepository.activateCandidateGenerationInCurrentTx(job, cacheEnabled, clock.millis()));
        RebuildJobRecord activated = reload(job.jobId());
        if (!cacheEnabled) {
            return new StepOutcome(activationStatus, activated, null);
        }
        StepOutcome cacheOutcome = stepInvalidateCache(activated);
        if (cacheOutcome.job().phase() == RebuildPhase.COMPLETED) {
            return new StepOutcome(activationStatus, cacheOutcome.job(), null);
        }
        return cacheOutcome;
    }

    private StepOutcome stepInvalidateCache(RebuildJobRecord job) {
        if (!cacheInvalidator.isCacheEnabled()) {
            transactionTemplate.executeWithoutResult(status ->
                    rebuildRepository.markJobCompletedAfterCacheInvalidationInCurrentTx(job, clock.millis()));
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        }
        try {
            cacheInvalidator.advanceEpoch();
            transactionTemplate.executeWithoutResult(status ->
                    rebuildRepository.markJobCompletedAfterCacheInvalidationInCurrentTx(job, clock.millis()));
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (Exception cacheEx) {
            String err = "CUTOVER_CACHE_EPOCH_INCR_" + cacheEx.getClass().getSimpleName();
            LOGGER.warn("event=search_rebuild_cache_invalidation_failed jobId={} error={}", job.jobId(), err);
            Optional<IndexGenerationState> state = consumerRepository.findGenerationState(job.aliasName());
            int currentAttempts = state.map(IndexGenerationState::activeCacheInvalidationAttempts).orElse(0);
            transactionTemplate.executeWithoutResult(status -> {
                consumerRepository.markGenerationCacheInvalidationRetry(
                        job.aliasName(),
                        job.candidateGeneration(),
                        currentAttempts,
                        err,
                        consumerProperties.getIndexer().getCacheCompensationBaseDelayMs(),
                        consumerProperties.getIndexer().getCacheCompensationMaxDelayMs(),
                        clock.millis()
                );
                rebuildRepository.recordTransientErrorInCurrentTx(job.jobId(), err, clock.millis());
            });
            return new StepOutcome(StepStatus.CACHE_INVALIDATION_PENDING, reload(job.jobId()), err);
        }
    }

    protected void afterAtomicAliasSwitchBeforeLocalCommit(RebuildJobRecord job) {
        // Extension hook for failure-injection tests simulating crash after ES _aliases before SQL activation.
    }

    protected void applySnapshotItemInTx(RebuildJobRecord job, SnapshotArticleItem item, long nowMs) {
        long gen = job.candidateGeneration();
        ArticleProjectionRecord current = consumerRepository.ensureAndLockProjection(gen, item.articleId(), nowMs);
        if (current.hasContent() && item.sourceRevision() <= current.contentRevision()) {
            // Idempotent page retry: only merge counter if strictly newer.
            if (item.counterRevision() > current.counterRevision()
                    && current.visibility() == ArticleVisibility.PUBLIC) {
                long nextProjRev = current.projectionRevision() + 1L;
                ArticleProjectionRecord merged = new ArticleProjectionRecord(
                        current.articleId(),
                        true,
                        current.contentRevision(),
                        ArticleVisibility.PUBLIC,
                        item.counterRevision(),
                        item.viewCount(),
                        nextProjRev,
                        current.title(),
                        current.summary(),
                        current.content(),
                        current.categoryId(),
                        current.categoryName(),
                        current.tagNames(),
                        current.thumbnail(),
                        current.isTop(),
                        current.createTimeMs(),
                        nowMs
                );
                consumerRepository.updateProjection(gen, merged);
                upsertCandidatePublicTask(job, merged, nowMs);
            }
            return;
        }

        long mergedCounterRevision = item.counterRevision() > current.counterRevision()
                ? item.counterRevision()
                : current.counterRevision();
        long mergedViewCount = item.counterRevision() > current.counterRevision()
                ? item.viewCount()
                : (current.counterRevision() == 0L && item.counterRevision() == 0L
                        ? item.viewCount()
                        : current.viewCount());

        long nextProjRev = current.projectionRevision() + 1L;
        ArticleProjectionRecord updated = new ArticleProjectionRecord(
                item.articleId(),
                true,
                item.sourceRevision(),
                ArticleVisibility.PUBLIC,
                mergedCounterRevision,
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
        consumerRepository.updateProjection(gen, updated);
        consumerRepository.advanceVersionBarrierIfHigher(
                item.articleId(), item.sourceRevision(), ArticleVisibility.PUBLIC, nowMs);
        upsertCandidatePublicTask(job, updated, nowMs);
    }

    protected void applyChangeEventInTx(
            RebuildJobRecord job, long commitSequence, ParsedContentEvent event, long nowMs) {
        long gen = job.candidateGeneration();
        String initialStatus = (event instanceof ParsedContentEvent.IgnoredNonSearchEvent)
                ? "IGNORED"
                : "PROCESSING";
        boolean inserted = rebuildRepository.insertRebuildEventIfAbsent(
                gen, commitSequence, event, initialStatus, nowMs);
        if (!inserted) {
            return;
        }
        if (event instanceof ParsedContentEvent.IgnoredNonSearchEvent) {
            return;
        }
        if (event instanceof ParsedContentEvent.PublicArticleEvent publicEvent) {
            ArticleProjectionRecord current = consumerRepository.ensureAndLockProjection(gen, publicEvent.articleId(), nowMs);
            if (publicEvent.sourceRevision() <= current.contentRevision()) {
                rebuildRepository.updateRebuildEventStatus(gen, publicEvent.eventId(), "STALE_IGNORED", nowMs);
                return;
            }
            long mergedCounterRev;
            long mergedViewCount;
            if (publicEvent.counterRevision() > current.counterRevision()) {
                mergedCounterRev = publicEvent.counterRevision();
                mergedViewCount = publicEvent.viewCount();
            } else if (!current.hasContent() && current.counterRevision() == 0L && publicEvent.counterRevision() == 0L) {
                mergedCounterRev = 0L;
                mergedViewCount = publicEvent.viewCount();
            } else {
                mergedCounterRev = current.counterRevision();
                mergedViewCount = current.viewCount();
            }
            long nextProjRev = current.projectionRevision() + 1L;
            ArticleProjectionRecord updated = new ArticleProjectionRecord(
                    publicEvent.articleId(),
                    true,
                    publicEvent.sourceRevision(),
                    ArticleVisibility.PUBLIC,
                    mergedCounterRev,
                    mergedViewCount,
                    nextProjRev,
                    publicEvent.title(),
                    publicEvent.summary(),
                    publicEvent.content(),
                    publicEvent.categoryId(),
                    publicEvent.categoryName(),
                    publicEvent.tagNames(),
                    publicEvent.thumbnail(),
                    publicEvent.isTop(),
                    publicEvent.createTimeMs(),
                    nowMs
            );
            consumerRepository.updateProjection(gen, updated);
            consumerRepository.advanceVersionBarrierIfHigher(
                    publicEvent.articleId(), publicEvent.sourceRevision(), ArticleVisibility.PUBLIC, nowMs);
            upsertCandidatePublicTask(job, updated, nowMs);
            rebuildRepository.updateRebuildEventStatus(gen, publicEvent.eventId(), "APPLIED", nowMs);
            return;
        }
        if (event instanceof ParsedContentEvent.HiddenArticleEvent hiddenEvent) {
            ArticleProjectionRecord current = consumerRepository.ensureAndLockProjection(gen, hiddenEvent.articleId(), nowMs);
            if (hiddenEvent.sourceRevision() <= current.contentRevision()) {
                rebuildRepository.updateRebuildEventStatus(gen, hiddenEvent.eventId(), "STALE_IGNORED", nowMs);
                return;
            }
            long nextProjRev = current.projectionRevision() + 1L;
            ArticleProjectionRecord updated = new ArticleProjectionRecord(
                    hiddenEvent.articleId(),
                    true,
                    hiddenEvent.sourceRevision(),
                    hiddenEvent.visibility(),
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
            consumerRepository.updateProjection(gen, updated);
            consumerRepository.advanceVersionBarrierIfHigher(
                    hiddenEvent.articleId(), hiddenEvent.sourceRevision(), hiddenEvent.visibility(), nowMs);
            SearchIndexDocument tombstone = SearchIndexDocument.tombstone(
                    updated.articleId(), updated.contentRevision());
            consumerRepository.upsertPendingIndexTask(
                    gen,
                    job.candidatePhysicalIndex(),
                    updated.articleId(),
                    nextProjRev,
                    SearchIndexOperation.TOMBSTONE,
                    serializeDocument(tombstone),
                    consumerProperties.getIndexer().getMaxAttempts(),
                    nowMs
            );
            rebuildRepository.updateRebuildEventStatus(gen, hiddenEvent.eventId(), "APPLIED", nowMs);
            return;
        }
        if (event instanceof ParsedContentEvent.CounterChangedEvent counterEvent) {
            ArticleProjectionRecord current = consumerRepository.ensureAndLockProjection(gen, counterEvent.articleId(), nowMs);
            if (counterEvent.counterRevision() <= current.counterRevision()) {
                rebuildRepository.updateRebuildEventStatus(gen, counterEvent.eventId(), "STALE_IGNORED", nowMs);
                return;
            }
            boolean searchableNow = current.hasContent() && current.visibility() == ArticleVisibility.PUBLIC;
            long nextProjRev = searchableNow ? current.projectionRevision() + 1L : current.projectionRevision();
            ArticleProjectionRecord updated = new ArticleProjectionRecord(
                    current.articleId(),
                    current.hasContent(),
                    current.contentRevision(),
                    current.visibility(),
                    counterEvent.counterRevision(),
                    counterEvent.viewCount(),
                    nextProjRev,
                    current.title(),
                    current.summary(),
                    current.content(),
                    current.categoryId(),
                    current.categoryName(),
                    current.tagNames(),
                    current.thumbnail(),
                    current.isTop(),
                    current.createTimeMs(),
                    nowMs
            );
            consumerRepository.updateProjection(gen, updated);
            if (searchableNow) {
                upsertCandidatePublicTask(job, updated, nowMs);
            }
            rebuildRepository.updateRebuildEventStatus(gen, counterEvent.eventId(), "APPLIED", nowMs);
        }
    }

    private void upsertCandidatePublicTask(RebuildJobRecord job, ArticleProjectionRecord projection, long nowMs) {
        SearchIndexDocument doc = SearchIndexDocument.upsert(
                projection.articleId(),
                projection.contentRevision(),
                projection.title(),
                projection.summary(),
                projection.content(),
                projection.categoryId(),
                projection.categoryName(),
                projection.tagNames(),
                projection.thumbnail(),
                projection.viewCount(),
                projection.createTimeMs()
        );
        consumerRepository.upsertPendingIndexTask(
                job.candidateGeneration(),
                job.candidatePhysicalIndex(),
                projection.articleId(),
                projection.projectionRevision(),
                SearchIndexOperation.UPSERT,
                serializeDocument(doc),
                consumerProperties.getIndexer().getMaxAttempts(),
                nowMs
        );
    }

    private void drainCandidateTasks(RebuildJobRecord job) {
        int maxLoops = Math.max(1, rebuildProperties.getMaxCandidateIndexDrainSteps());
        for (int i = 0; i < maxLoops; i++) {
            SearchIndexTaskWorker.WorkerCycleSummary summary = indexTaskWorker.processCandidateGenerationOnce(
                    job.candidateGeneration(), job.candidatePhysicalIndex());
            if (summary.claimedCount() == 0) {
                break;
            }
        }
    }

    private String verifyCandidateIndexOnSearchPath(RebuildJobRecord job) {
        long expectedPublic = rebuildRepository.countCandidateProjections(job.candidateGeneration(), true);
        long expectedHidden = rebuildRepository.countCandidateProjections(job.candidateGeneration(), false);
        long actualPublic = indexAdminClient.countDocumentsBySearchable(job.candidatePhysicalIndex(), true);
        long actualHidden = indexAdminClient.countDocumentsBySearchable(job.candidatePhysicalIndex(), false);
        if (actualPublic != expectedPublic) {
            return "PUBLIC_COUNT_MISMATCH:expected=" + expectedPublic + ",actual=" + actualPublic;
        }
        if (actualHidden != expectedHidden) {
            return "TOMBSTONE_COUNT_MISMATCH:expected=" + expectedHidden + ",actual=" + actualHidden;
        }

        int sampleLimit = Math.max(1, rebuildProperties.getVerificationSampleSize());
        List<ArticleProjectionRecord> publicSamples = rebuildRepository.sampleCandidateProjections(
                job.candidateGeneration(), true, sampleLimit);
        if (!publicSamples.isEmpty()) {
            List<String> ids = publicSamples.stream().map(p -> Long.toString(p.articleId())).toList();
            Map<String, SearchVisibleDoc> docs = indexAdminClient.fetchSearchVisibleDocsByIds(
                    job.candidatePhysicalIndex(), ids);
            for (ArticleProjectionRecord proj : publicSamples) {
                String docId = Long.toString(proj.articleId());
                SearchVisibleDoc doc = docs.get(docId);
                if (doc == null || doc.source() == null) {
                    return "MISSING_PUBLIC_SAMPLE:" + docId;
                }
                if (!Objects.equals(doc.version(), proj.projectionRevision())) {
                    return "PUBLIC_VERSION_MISMATCH:" + docId + ":expected=" + proj.projectionRevision() + ",actual=" + doc.version();
                }
                JsonNode src = doc.source();
                if (!src.path("searchable").asBoolean(false)) {
                    return "PUBLIC_NOT_SEARCHABLE:" + docId;
                }
                if (!Long.toString(proj.contentRevision()).equals(src.path("sourceRevision").asText())) {
                    return "PUBLIC_SOURCE_REV_MISMATCH:" + docId;
                }
                if (!Objects.equals(proj.title(), src.path("title").asText(null))) {
                    return "PUBLIC_TITLE_MISMATCH:" + docId;
                }
                if (proj.viewCount() != src.path("viewCount").asLong(Long.MIN_VALUE)) {
                    return "PUBLIC_VIEW_COUNT_MISMATCH:" + docId;
                }
            }
        }

        List<ArticleProjectionRecord> hiddenSamples = rebuildRepository.sampleCandidateProjections(
                job.candidateGeneration(), false, sampleLimit);
        if (!hiddenSamples.isEmpty()) {
            List<String> ids = hiddenSamples.stream().map(p -> Long.toString(p.articleId())).toList();
            Map<String, SearchVisibleDoc> docs = indexAdminClient.fetchSearchVisibleDocsByIds(
                    job.candidatePhysicalIndex(), ids);
            for (ArticleProjectionRecord proj : hiddenSamples) {
                String docId = Long.toString(proj.articleId());
                SearchVisibleDoc doc = docs.get(docId);
                if (doc == null || doc.source() == null) {
                    return "MISSING_TOMBSTONE_SAMPLE:" + docId;
                }
                if (!Objects.equals(doc.version(), proj.projectionRevision())) {
                    return "TOMBSTONE_VERSION_MISMATCH:" + docId;
                }
                JsonNode src = doc.source();
                if (src.path("searchable").asBoolean(true)) {
                    return "TOMBSTONE_STILL_SEARCHABLE:" + docId;
                }
                if (!Long.toString(proj.contentRevision()).equals(src.path("sourceRevision").asText())) {
                    return "TOMBSTONE_SOURCE_REV_MISMATCH:" + docId;
                }
                for (String forbidden : TOMBSTONE_FORBIDDEN_FIELDS) {
                    JsonNode field = src.get(forbidden);
                    if (field != null && !field.isNull()) {
                        return "TOMBSTONE_LEAKED_FIELD:" + docId + ":" + forbidden;
                    }
                }
            }
        }
        return null;
    }

    private StepOutcome handleContentSnapshotException(RebuildJobRecord job, ContentSnapshotException ex) {
        if (ex.isExpired410()) {
            String newJobId = generateJobId();
            String newClientRequestId = generateClientRequestId();
            long leaseDurationMs = Math.max(5, rebuildProperties.getJobLeaseSeconds()) * 1000L;
            String reason = "HTTP_410_" + ex.errorCode() + ":" + ex.getMessage();
            LOGGER.warn("event=search_rebuild_expired_410_restarting oldJobId={} newJobId={} reason={}",
                    job.jobId(), newJobId, reason);
            RebuildJobRecord replacement = transactionTemplate.execute(status ->
                    rebuildRepository.expireAndRestartJobInCurrentTx(
                            job, newJobId, newClientRequestId, instanceId, leaseDurationMs, reason, clock.millis()));
            return new StepOutcome(StepStatus.EXPIRED_410_RESTARTED, replacement, reason);
        }
        String err = "CONTENT_HTTP_" + ex.httpStatus() + "_" + ex.errorCode() + ":" + ex.getMessage();
        if (ex.isRetryable()) {
            recordTransientError(job.jobId(), err);
            return new StepOutcome(StepStatus.RETRYABLE_ERROR, reload(job.jobId()), err);
        }
        transactionTemplate.executeWithoutResult(status ->
                rebuildRepository.markJobFailedInCurrentTx(job.jobId(), job.aliasName(), err, clock.millis()));
        return new StepOutcome(StepStatus.FAILED, reload(job.jobId()), err);
    }

    private String resolveInitialPhysicalIndex() {
        Optional<IndexGenerationState> existing = consumerRepository.findGenerationState(aliasName);
        if (existing.isPresent()) {
            return existing.get().activePhysicalIndex();
        }
        try {
            Set<String> targets = indexAdminClient.getAliasTargets(aliasName);
            if (targets.size() == 1) {
                return targets.iterator().next();
            }
        } catch (Exception ignored) {
            // Fall back to canonical _g1 physical index naming.
        }
        return SearchRebuildRepository.physicalIndexName(aliasName, SearchConsumerRepository.DEFAULT_GENERATION);
    }

    private void recordTransientError(String jobId, String err) {
        transactionTemplate.executeWithoutResult(status ->
                rebuildRepository.recordTransientErrorInCurrentTx(jobId, err, clock.millis()));
    }

    private RebuildJobRecord reload(String jobId) {
        return rebuildRepository.findJob(jobId).orElseThrow();
    }

    private String serializeDocument(SearchIndexDocument doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize SearchIndexDocument", ex);
        }
    }

    private static String generateJobId() {
        return "rb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String generateClientRequestId() {
        return "search-rb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String resolveInstanceId(String configured) {
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        String host = "instance";
        try {
            host = InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9._-]", "-");
        } catch (Exception ignored) {
            // Fall back to default host token.
        }
        return "knode-search-rb-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record ParsedCommit(long commitSequence, List<ParsedContentEvent> events) {
    }

    public enum StepStatus {
        NO_ACTIVE_JOB,
        LEASE_HELD_BY_OTHER,
        ALREADY_TERMINAL,
        PROGRESSED,
        WAITING_RETRY,
        RETRYABLE_ERROR,
        EXPIRED_410_RESTARTED,
        PRE_SWITCH_FAILED_REVERTED,
        ALIAS_SWITCH_AMBIGUOUS,
        ALIAS_SWITCHED_AND_ACTIVATED,
        ALIAS_RECONCILED_AND_ACTIVATED,
        CACHE_INVALIDATION_PENDING,
        FAILED
    }

    public record StepOutcome(StepStatus status, RebuildJobRecord job, String detail) {
    }
}
