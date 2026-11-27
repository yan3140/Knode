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
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
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
import com.knode.search.rebuild.es.AliasSwitchResult;
import com.knode.search.rebuild.es.SearchIndexAdminClient;
import com.knode.search.rebuild.es.SearchVisibleDoc;
import com.knode.search.rebuild.persistence.RebuildJobRecord;
import com.knode.search.rebuild.persistence.RebuildPhase;
import com.knode.search.rebuild.persistence.SearchRebuildRepository;
import com.knode.search.rebuild.persistence.StaleRebuildLeaseException;
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
import java.util.LinkedHashSet;
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
            return recordTransientErrorOrFenced(job, RebuildPhase.INIT, false, false, err);
        }

        QueryBudget budget = QueryBudget.start(Duration.ofMillis(rebuildProperties.getRequestTimeoutMs()));
        try {
            SnapshotHandle handle = snapshotClient.createSearchRebuildSnapshot(
                    job.clientRequestId(), budget, "rb-create-" + job.jobId());
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, RebuildPhase.INIT, false, false, nowMs);
                rebuildRepository.updateSnapshotHandleInCurrentTx(
                        job.jobId(),
                        handle.snapshotId(),
                        handle.incrementalCursor(),
                        handle.expiresAt().toEpochMilli(),
                        nowMs
                );
            });
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
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
                // Fence BEFORE writing any projection, index task, or page checkpoint!
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, RebuildPhase.SNAPSHOT_IMPORTING, true, false, nowMs);
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
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        } catch (Exception dbEx) {
            String err = "SNAPSHOT_PAGE_PERSIST_FAILED:" + dbEx.getMessage();
            LOGGER.warn("event=search_rebuild_snapshot_page_failed jobId={} error={}", job.jobId(), err);
            return recordTransientErrorOrFenced(job, RebuildPhase.SNAPSHOT_IMPORTING, true, false, err);
        }
    }

    private StepOutcome stepCatchupChanges(RebuildJobRecord job, boolean finalCatchup) {
        RebuildPhase expectedPhase = finalCatchup ? RebuildPhase.FINAL_CATCHUP : RebuildPhase.INCREMENTAL_CATCHUP;
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
                    return recordTransientErrorOrFenced(job, expectedPhase, false, true, err);
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
                // Fence BEFORE writing any rebuild event log, projection, index task, or cursor checkpoint!
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, expectedPhase, false, true, nowMs);
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
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        } catch (Exception dbEx) {
            String err = "CHANGE_BATCH_PERSIST_FAILED:" + dbEx.getMessage();
            LOGGER.warn("event=search_rebuild_change_batch_failed jobId={} error={}", job.jobId(), err);
            return recordTransientErrorOrFenced(job, expectedPhase, false, true, err);
        }
    }

    private StepOutcome stepDrainCandidateIndexing(RebuildJobRecord job) {
        drainCandidateTasks(job);
        long dead = consumerRepository.countDeadTasks(job.candidateGeneration());
        if (dead > 0L) {
            String err = "CANDIDATE_INDEX_DEAD_TASKS:" + dead;
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    long nowMs = clock.millis();
                    rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                            job, instanceId, RebuildPhase.CANDIDATE_INDEXING, false, false, nowMs);
                    rebuildRepository.markJobFailedInCurrentTx(job.jobId(), job.aliasName(), err, nowMs);
                });
                return new StepOutcome(StepStatus.FAILED, reload(job.jobId()), err);
            } catch (StaleRebuildLeaseException fenced) {
                return leaseFencedOutcome(job, fenced);
            }
        }
        long incomplete = consumerRepository.countIncompleteTasks(job.candidateGeneration());
        if (incomplete == 0L) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    long nowMs = clock.millis();
                    rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                            job, instanceId, RebuildPhase.CANDIDATE_INDEXING, false, false, nowMs);
                    rebuildRepository.updatePhaseInCurrentTx(
                            job.jobId(), RebuildPhase.CUTOVER_PAUSING, null, nowMs);
                });
                return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
            } catch (StaleRebuildLeaseException fenced) {
                return leaseFencedOutcome(job, fenced);
            }
        }
        return new StepOutcome(StepStatus.WAITING_RETRY, reload(job.jobId()), "INCOMPLETE_TASKS:" + incomplete);
    }

    private StepOutcome stepEnterCutoverPause(RebuildJobRecord job) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, RebuildPhase.CUTOVER_PAUSING, false, false, nowMs);
                rebuildRepository.enterCutoverPauseInCurrentTx(job, nowMs);
            });
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        } catch (Exception ex) {
            String err = "CUTOVER_PAUSE_FAILED:" + ex.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.CUTOVER_PAUSING, false, false, err);
        }
    }

    private StepOutcome stepVerifyCandidate(RebuildJobRecord job) {
        // First ensure any candidate index tasks added by FINAL_CATCHUP are drained to candidate ES index.
        drainCandidateTasks(job);
        long dead = consumerRepository.countDeadTasks(job.candidateGeneration());
        if (dead > 0L) {
            String err = "FINAL_CANDIDATE_DEAD_TASKS:" + dead;
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    long nowMs = clock.millis();
                    rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                            job, instanceId, RebuildPhase.VERIFYING_CANDIDATE, false, false, nowMs);
                    rebuildRepository.markJobFailedInCurrentTx(job.jobId(), job.aliasName(), err, nowMs);
                });
                return new StepOutcome(StepStatus.FAILED, reload(job.jobId()), err);
            } catch (StaleRebuildLeaseException fenced) {
                return leaseFencedOutcome(job, fenced);
            }
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
                transactionTemplate.executeWithoutResult(status -> {
                    long nowMs = clock.millis();
                    rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                            job, instanceId, RebuildPhase.VERIFYING_CANDIDATE, false, false, nowMs);
                    rebuildRepository.revertConfirmedPreSwitchFailureInCurrentTx(
                            job, true, "CANDIDATE_VERIFY_FAILED:" + mismatch, nowMs);
                });
                return new StepOutcome(StepStatus.FAILED, reload(job.jobId()), mismatch);
            }
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        } catch (Exception ex) {
            String err = "CANDIDATE_VERIFY_ERROR:" + ex.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.VERIFYING_CANDIDATE, false, false, err);
        }

        // Persist SWITCHING_ALIAS intent BEFORE issuing ES _aliases!
        try {
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, RebuildPhase.VERIFYING_CANDIDATE, false, false, nowMs);
                rebuildRepository.enterSwitchingAliasInCurrentTx(job, nowMs);
            });
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }
    }

    private StepOutcome stepSwitchAliasOrReconcile(RebuildJobRecord job) {
        String attachFence = SearchIndexAdminClient.attachFenceAlias(job.candidatePhysicalIndex());
        String bypassOwner = cutoverBypassOwner(job);

        Set<String> currentTargets;
        try {
            currentTargets = indexAdminClient.getAliasTargets(job.aliasName());
        } catch (Exception ex) {
            String err = "ALIAS_RECONCILE_READ_FAILED:" + ex.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }

        // Case 1: ES alias ALREADY points solely to candidatePhysicalIndex -> verify lease, atomically adopt/ensure
        // attempt-scoped cutover_bypass (safely taking over any legacy R2 "<alias>:g<N>" token), disarm attach fence,
        // then activate!
        if (currentTargets.equals(Set.of(job.candidatePhysicalIndex()))) {
            try {
                verifyLeaseActiveInTx(job, RebuildPhase.SWITCHING_ALIAS);
            } catch (StaleRebuildLeaseException fenced) {
                return leaseFencedOutcome(job, fenced);
            }
            if (cacheInvalidator.isCacheEnabled()) {
                try {
                    cacheInvalidator.enterCutoverBypass(
                            bypassOwner, job.candidateGeneration(), effectiveSwitchAttempt(job));
                } catch (Exception bypassEx) {
                    String err = "CUTOVER_CACHE_BYPASS_SET_FAILED:" + bypassEx.getMessage();
                    LOGGER.warn("event=search_rebuild_cutover_bypass_failed jobId={} error={}", job.jobId(), err);
                    return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                            StepStatus.ALIAS_SWITCH_AMBIGUOUS);
                }
            }
            StepOutcome fenceHold = ensureAttachFenceDisarmedOrHold(job, attachFence);
            if (fenceHold != null) {
                return fenceHold;
            }
            LOGGER.info("event=search_rebuild_alias_reconciled_forward jobId={} alias={} target={}",
                    job.jobId(), job.aliasName(), job.candidatePhysicalIndex());
            return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        }

        // Case 2: Partial alias binding (e.g., both source and candidate attached after partial failure) ->
        // atomically remove stale non-candidate bindings (with must_exist=true) and re-verify!
        if (currentTargets.contains(job.candidatePhysicalIndex()) && currentTargets.size() > 1) {
            return repairPartialAliasWithCandidatePresent(job, currentTargets, attachFence);
        }

        // Case 3: Previous alias switch request had uncertain/timed-out/unacknowledged outcome!
        if (job.aliasSwitchUncertain()) {
            if (currentTargets.isEmpty()) {
                return repairEmptyAliasToCandidate(job, attachFence);
            }
            String err = "ALIAS_SWITCH_IN_FLIGHT_UNCERTAIN:waiting_for_es_reconcile:targets=" + currentTargets;
            LOGGER.warn("event=search_rebuild_alias_still_uncertain jobId={} alias={} targets={}",
                    job.jobId(), job.aliasName(), currentTargets);
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }

        // Case 4: Fresh pre-switch state (empty on bootstrap or pointing solely to sourcePhysicalIndex).
        boolean validPreSwitch = currentTargets.isEmpty()
                || currentTargets.equals(Set.of(job.sourcePhysicalIndex()));
        if (!validPreSwitch) {
            String err = "UNEXPECTED_ALIAS_TARGETS:" + currentTargets;
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }

        try {
            verifyLeaseActiveInTx(job, RebuildPhase.SWITCHING_ALIAS);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }

        if (currentTargets.isEmpty()) {
            try {
                Set<String> fenceTargets = indexAdminClient.getAliasTargets(attachFence);
                if (!fenceTargets.contains(job.candidatePhysicalIndex())) {
                    String err = "ALIAS_BOOTSTRAP_ATTACH_FENCE_MISSING:fence=" + attachFence;
                    return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                            StepStatus.ALIAS_SWITCH_AMBIGUOUS);
                }
            } catch (Exception fenceReadEx) {
                String err = "ALIAS_BOOTSTRAP_FENCE_READ_FAILED:" + fenceReadEx.getMessage();
                return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                        StepStatus.ALIAS_SWITCH_AMBIGUOUS);
            }
        }

        if (cacheInvalidator.isCacheEnabled()) {
            beforeEnterCutoverBypassInSwitch(job, bypassOwner);
            try {
                cacheInvalidator.enterCutoverBypass(
                        bypassOwner, job.candidateGeneration(), effectiveSwitchAttempt(job));
            } catch (Exception bypassEx) {
                String err = "CUTOVER_CACHE_BYPASS_SET_FAILED:" + bypassEx.getMessage();
                LOGGER.warn("event=search_rebuild_cutover_bypass_failed jobId={} error={}", job.jobId(), err);
                return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err);
            }
        }

        try {
            transactionTemplate.executeWithoutResult(status ->
                    rebuildRepository.markAliasSwitchUncertainInCurrentTx(
                            job, instanceId, true, null, clock.millis()));
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }

        AliasSwitchResult switchResult = indexAdminClient.switchAliasAtomicallyWithFence(
                job.aliasName(), currentTargets, job.candidatePhysicalIndex(), attachFence);

        if (switchResult.isAcknowledged()) {
            afterAtomicAliasSwitchBeforeLocalCommit(job);
            Set<String> confirmedTargets;
            try {
                confirmedTargets = indexAdminClient.getAliasTargets(job.aliasName());
            } catch (Exception verifyEx) {
                String err = "ALIAS_POST_SWITCH_VERIFY_FAILED:" + verifyEx.getMessage();
                return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                        StepStatus.ALIAS_SWITCH_AMBIGUOUS);
            }
            if (confirmedTargets.equals(Set.of(job.candidatePhysicalIndex()))) {
                StepOutcome fenceHold = ensureAttachFenceDisarmedOrHold(job, attachFence);
                if (fenceHold != null) {
                    return fenceHold;
                }
                return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_SWITCHED_AND_ACTIVATED);
            }
            String err = "ALIAS_POST_SWITCH_TARGET_MISMATCH:" + confirmedTargets;
            LOGGER.warn("event=search_rebuild_alias_post_verify_mismatch jobId={} alias={} targets={}",
                    job.jobId(), job.aliasName(), confirmedTargets);
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }

        String switchErr = "ALIAS_SWITCH_" + switchResult.status().name() + ":" + switchResult.detail();
        LOGGER.warn("event=search_rebuild_alias_switch_non_ack jobId={} alias={} status={} detail={}",
                job.jobId(), job.aliasName(), switchResult.status(), switchResult.detail());

        Set<String> postTargets;
        try {
            postTargets = indexAdminClient.getAliasTargets(job.aliasName());
        } catch (Exception postReadEx) {
            String ambErr = switchErr + ";postCheck=" + postReadEx.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, ambErr,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }

        if (postTargets.equals(Set.of(job.candidatePhysicalIndex()))) {
            StepOutcome fenceHold = ensureAttachFenceDisarmedOrHold(job, attachFence);
            if (fenceHold != null) {
                return fenceHold;
            }
            LOGGER.info("event=search_rebuild_alias_reconciled_after_non_ack jobId={} alias={} target={}",
                    job.jobId(), job.aliasName(), job.candidatePhysicalIndex());
            return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        }

        if (switchResult.isConfirmedPreSwitchRejected()
                && (postTargets.isEmpty() || postTargets.equals(Set.of(job.sourcePhysicalIndex())))) {
            boolean exhausted = job.switchAttempts() >= rebuildProperties.getMaxSwitchAttempts();
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    long nowMs = clock.millis();
                    rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                            job, instanceId, RebuildPhase.SWITCHING_ALIAS, false, false, nowMs);
                    rebuildRepository.revertConfirmedPreSwitchFailureInCurrentTx(
                            job, exhausted, switchErr, nowMs);
                });
            } catch (StaleRebuildLeaseException fenced) {
                return leaseFencedOutcome(job, fenced);
            }
            beforeClearCutoverBypassAfterPreSwitchRevert(job, bypassOwner);
            clearCutoverBypassBestEffort(job.jobId(), bypassOwner);
            return new StepOutcome(
                    exhausted ? StepStatus.FAILED : StepStatus.PRE_SWITCH_FAILED_REVERTED,
                    reload(job.jobId()),
                    switchErr
            );
        }

        String holdErr = switchErr + ";postTargets=" + postTargets;
        return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, holdErr,
                StepStatus.ALIAS_SWITCH_AMBIGUOUS);
    }

    private StepOutcome repairPartialAliasWithCandidatePresent(
            RebuildJobRecord job, Set<String> currentTargets, String attachFence) {
        try {
            verifyLeaseActiveInTx(job, RebuildPhase.SWITCHING_ALIAS);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }
        if (cacheInvalidator.isCacheEnabled()) {
            try {
                cacheInvalidator.enterCutoverBypass(
                        cutoverBypassOwner(job), job.candidateGeneration(), effectiveSwitchAttempt(job));
            } catch (Exception bypassEx) {
                String err = "CUTOVER_CACHE_BYPASS_SET_FAILED:" + bypassEx.getMessage();
                return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                        StepStatus.ALIAS_SWITCH_AMBIGUOUS);
            }
        }
        Set<String> staleIndices = new LinkedHashSet<>();
        for (String target : currentTargets) {
            if (!target.equals(job.candidatePhysicalIndex())) {
                staleIndices.add(target);
            }
        }
        LOGGER.warn("event=search_rebuild_alias_repairing_partial jobId={} alias={} removing={} keeping={}",
                job.jobId(), job.aliasName(), staleIndices, job.candidatePhysicalIndex());
        AliasSwitchResult repairResult = indexAdminClient.removeAliasBindingsAtomically(job.aliasName(), staleIndices);
        Set<String> postTargets;
        try {
            postTargets = indexAdminClient.getAliasTargets(job.aliasName());
        } catch (Exception readEx) {
            String err = "ALIAS_PARTIAL_REPAIR_VERIFY_FAILED:" + readEx.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }
        if (postTargets.equals(Set.of(job.candidatePhysicalIndex()))) {
            StepOutcome fenceHold = ensureAttachFenceDisarmedOrHold(job, attachFence);
            if (fenceHold != null) {
                return fenceHold;
            }
            LOGGER.info("event=search_rebuild_alias_partial_repaired jobId={} alias={} target={}",
                    job.jobId(), job.aliasName(), job.candidatePhysicalIndex());
            return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        }
        String err = "ALIAS_PARTIAL_REPAIR_INCOMPLETE:" + repairResult.status() + ":" + repairResult.detail()
                + ";postTargets=" + postTargets;
        return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                StepStatus.ALIAS_SWITCH_AMBIGUOUS);
    }

    private StepOutcome repairEmptyAliasToCandidate(RebuildJobRecord job, String attachFence) {
        try {
            verifyLeaseActiveInTx(job, RebuildPhase.SWITCHING_ALIAS);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }
        if (cacheInvalidator.isCacheEnabled()) {
            try {
                cacheInvalidator.enterCutoverBypass(
                        cutoverBypassOwner(job), job.candidateGeneration(), effectiveSwitchAttempt(job));
            } catch (Exception bypassEx) {
                String err = "CUTOVER_CACHE_BYPASS_SET_FAILED:" + bypassEx.getMessage();
                return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                        StepStatus.ALIAS_SWITCH_AMBIGUOUS);
            }
        }
        Set<String> fenceTargets;
        try {
            fenceTargets = indexAdminClient.getAliasTargets(attachFence);
        } catch (Exception fenceReadEx) {
            String err = "ALIAS_EMPTY_REPAIR_FENCE_READ_FAILED:" + fenceReadEx.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }
        if (!fenceTargets.contains(job.candidatePhysicalIndex())) {
            try {
                Set<String> recheckTargets = indexAdminClient.getAliasTargets(job.aliasName());
                if (recheckTargets.equals(Set.of(job.candidatePhysicalIndex()))) {
                    StepOutcome fenceHold = ensureAttachFenceDisarmedOrHold(job, attachFence);
                    if (fenceHold != null) {
                        return fenceHold;
                    }
                    return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
                }
                String err = "ALIAS_EMPTY_REPAIR_FENCE_CONSUMED:fence=" + attachFence + ";targets=" + recheckTargets;
                return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                        StepStatus.ALIAS_SWITCH_AMBIGUOUS);
            } catch (Exception recheckEx) {
                String err = "ALIAS_EMPTY_REPAIR_RECHECK_FAILED:" + recheckEx.getMessage();
                return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                        StepStatus.ALIAS_SWITCH_AMBIGUOUS);
            }
        }

        AliasSwitchResult addResult = indexAdminClient.switchAliasAtomicallyWithFence(
                job.aliasName(), Set.of(), job.candidatePhysicalIndex(), attachFence);
        Set<String> postTargets;
        try {
            postTargets = indexAdminClient.getAliasTargets(job.aliasName());
        } catch (Exception readEx) {
            String err = "ALIAS_EMPTY_REPAIR_VERIFY_FAILED:" + readEx.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }
        if (postTargets.equals(Set.of(job.candidatePhysicalIndex()))) {
            StepOutcome fenceHold = ensureAttachFenceDisarmedOrHold(job, attachFence);
            if (fenceHold != null) {
                return fenceHold;
            }
            return activateCandidateAndAttemptCacheInvalidation(job, StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        }
        String err = "ALIAS_EMPTY_REPAIR_INCOMPLETE:" + addResult.status() + ":" + addResult.detail()
                + ";postTargets=" + postTargets;
        return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                StepStatus.ALIAS_SWITCH_AMBIGUOUS);
    }

    private StepOutcome ensureAttachFenceDisarmedOrHold(RebuildJobRecord job, String attachFence) {
        Set<String> fenceTargets;
        try {
            fenceTargets = indexAdminClient.getAliasTargets(attachFence);
        } catch (Exception readEx) {
            String err = "ALIAS_ATTACH_FENCE_VERIFY_FAILED:" + readEx.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }
        if (fenceTargets.isEmpty()) {
            return null;
        }
        LOGGER.warn("event=search_rebuild_disarming_remaining_attach_fence jobId={} fence={} targets={}",
                job.jobId(), attachFence, fenceTargets);
        AliasSwitchResult disarm = indexAdminClient.removeAliasBindingsAtomically(attachFence, fenceTargets);
        try {
            Set<String> remaining = indexAdminClient.getAliasTargets(attachFence);
            if (remaining.isEmpty()) {
                return null;
            }
            String err = "ALIAS_ATTACH_FENCE_DISARM_INCOMPLETE:" + disarm.status() + ":" + disarm.detail()
                    + ";remaining=" + remaining;
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        } catch (Exception verifyEx) {
            String err = "ALIAS_ATTACH_FENCE_DISARM_VERIFY_FAILED:" + verifyEx.getMessage();
            return recordTransientErrorOrFenced(job, RebuildPhase.SWITCHING_ALIAS, false, false, err,
                    StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        }
    }

    private StepOutcome activateCandidateAndAttemptCacheInvalidation(
            RebuildJobRecord job, StepStatus activationStatus) {
        boolean cacheEnabled = cacheInvalidator.isCacheEnabled();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, RebuildPhase.SWITCHING_ALIAS, false, false, nowMs);
                rebuildRepository.activateCandidateGenerationInCurrentTx(
                        job, cacheEnabled, cutoverBypassOwner(job), nowMs);
            });
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }
        RebuildJobRecord activated = reload(job.jobId());
        if (!cacheEnabled) {
            return new StepOutcome(activationStatus, activated, null);
        }
        StepOutcome cacheOutcome = stepInvalidateCache(activated);
        if (cacheOutcome.job() != null && cacheOutcome.job().phase() == RebuildPhase.COMPLETED) {
            return new StepOutcome(activationStatus, cacheOutcome.job(), null);
        }
        return cacheOutcome;
    }

    private StepOutcome stepInvalidateCache(RebuildJobRecord job) {
        if (!cacheInvalidator.isCacheEnabled()) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    long nowMs = clock.millis();
                    rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                            job, instanceId, RebuildPhase.CACHE_INVALIDATING, false, false, nowMs);
                    rebuildRepository.markJobCompletedAfterCacheInvalidationInCurrentTx(job, nowMs);
                });
                return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
            } catch (StaleRebuildLeaseException fenced) {
                return leaseFencedOutcome(job, fenced);
            }
        }

        try {
            verifyLeaseActiveInTx(job, RebuildPhase.CACHE_INVALIDATING);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }

        Optional<IndexGenerationState> currentGen = consumerRepository.findGenerationState(job.aliasName());
        String bypassOwner = resolveStepInvalidateCacheBypassOwner(job, currentGen);
        boolean alreadyCompensated = currentGen.isPresent()
                && currentGen.get().activeGeneration() == job.candidateGeneration()
                && currentGen.get().activeCacheInvalidationStatus() == CacheInvalidationStatus.DONE;
        try {
            if (alreadyCompensated) {
                cacheInvalidator.clearCutoverBypassIfOwned(bypassOwner);
            } else {
                cacheInvalidator.advanceEpochAndClearCutoverBypassIfOwned(bypassOwner);
            }
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, RebuildPhase.CACHE_INVALIDATING, false, false, nowMs);
                rebuildRepository.markJobCompletedAfterCacheInvalidationInCurrentTx(job, nowMs);
            });
            return new StepOutcome(StepStatus.PROGRESSED, reload(job.jobId()), null);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        } catch (Exception cacheEx) {
            String err = "CUTOVER_CACHE_EPOCH_INCR_" + cacheEx.getClass().getSimpleName();
            LOGGER.warn("event=search_rebuild_cache_invalidation_failed jobId={} error={}", job.jobId(), err);
            Optional<IndexGenerationState> state = consumerRepository.findGenerationState(job.aliasName());
            int currentAttempts = state.map(IndexGenerationState::activeCacheInvalidationAttempts).orElse(0);
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    long nowMs = clock.millis();
                    rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                            job, instanceId, RebuildPhase.CACHE_INVALIDATING, false, false, nowMs);
                    consumerRepository.markGenerationCacheInvalidationRetry(
                            job.aliasName(),
                            job.candidateGeneration(),
                            currentAttempts,
                            err,
                            consumerProperties.getIndexer().getCacheCompensationBaseDelayMs(),
                            consumerProperties.getIndexer().getCacheCompensationMaxDelayMs(),
                            nowMs
                    );
                    rebuildRepository.recordTransientErrorInCurrentTx(job.jobId(), err, nowMs);
                });
            } catch (StaleRebuildLeaseException fenced) {
                return leaseFencedOutcome(job, fenced);
            }
            return new StepOutcome(StepStatus.CACHE_INVALIDATION_PENDING, reload(job.jobId()), err);
        }
    }

    protected void afterAtomicAliasSwitchBeforeLocalCommit(RebuildJobRecord job) {
        // Extension hook for failure-injection tests simulating crash after ES _aliases before SQL activation.
    }

    protected void beforeEnterCutoverBypassInSwitch(RebuildJobRecord job, String bypassOwner) {
        // Extension hook for concurrency tests pausing right after lease verification and before Redis bypass write.
    }

    protected void beforeClearCutoverBypassAfterPreSwitchRevert(RebuildJobRecord job, String bypassOwner) {
        // Extension hook for concurrency tests pausing a pre-switch rollback right before Redis bypass cleanup.
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
        // Candidate generation versions stay isolated in search_article_projection(candidate_generation, article_id)
        // and are promoted to search_article_version_barrier atomically on cutover activation.
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
            // Candidate generation versions stay isolated until cutover activation.
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
            // Candidate generation hidden/delete versions stay isolated until cutover activation.
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
        boolean verifySnapCursor = job.phase() == RebuildPhase.SNAPSHOT_IMPORTING;
        boolean verifyIncCursor = job.phase() == RebuildPhase.INCREMENTAL_CATCHUP
                || job.phase() == RebuildPhase.FINAL_CATCHUP;
        if (ex.isExpired410()) {
            String newJobId = generateJobId();
            String newClientRequestId = generateClientRequestId();
            long leaseDurationMs = Math.max(5, rebuildProperties.getJobLeaseSeconds()) * 1000L;
            String reason = "HTTP_410_" + ex.errorCode() + ":" + ex.getMessage();
            LOGGER.warn("event=search_rebuild_expired_410_restarting oldJobId={} newJobId={} reason={}",
                    job.jobId(), newJobId, reason);
            try {
                RebuildJobRecord replacement = transactionTemplate.execute(status -> {
                    long nowMs = clock.millis();
                    rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                            job, instanceId, job.phase(), verifySnapCursor, verifyIncCursor, nowMs);
                    return rebuildRepository.expireAndRestartJobInCurrentTx(
                            job, newJobId, newClientRequestId, instanceId, leaseDurationMs, reason, nowMs);
                });
                return new StepOutcome(StepStatus.EXPIRED_410_RESTARTED, replacement, reason);
            } catch (StaleRebuildLeaseException fenced) {
                return leaseFencedOutcome(job, fenced);
            }
        }
        String err = "CONTENT_HTTP_" + ex.httpStatus() + "_" + ex.errorCode() + ":" + ex.getMessage();
        if (ex.isRetryable()) {
            return recordTransientErrorOrFenced(job, job.phase(), verifySnapCursor, verifyIncCursor, err);
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, job.phase(), verifySnapCursor, verifyIncCursor, nowMs);
                rebuildRepository.markJobFailedInCurrentTx(job.jobId(), job.aliasName(), err, nowMs);
            });
            return new StepOutcome(StepStatus.FAILED, reload(job.jobId()), err);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }
    }

    private void verifyLeaseActiveInTx(RebuildJobRecord job, RebuildPhase expectedPhase) {
        transactionTemplate.executeWithoutResult(status ->
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, expectedPhase, false, false, clock.millis()));
    }

    private StepOutcome recordTransientErrorOrFenced(
            RebuildJobRecord job,
            RebuildPhase expectedPhase,
            boolean verifySnapshotCursor,
            boolean verifyIncrementalCursor,
            String err) {
        return recordTransientErrorOrFenced(
                job, expectedPhase, verifySnapshotCursor, verifyIncrementalCursor, err, StepStatus.RETRYABLE_ERROR);
    }

    private StepOutcome recordTransientErrorOrFenced(
            RebuildJobRecord job,
            RebuildPhase expectedPhase,
            boolean verifySnapshotCursor,
            boolean verifyIncrementalCursor,
            String err,
            StepStatus statusWhenHeld) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                long nowMs = clock.millis();
                rebuildRepository.lockAndVerifyLeaseInCurrentTx(
                        job, instanceId, expectedPhase, verifySnapshotCursor, verifyIncrementalCursor, nowMs);
                rebuildRepository.recordTransientErrorInCurrentTx(job.jobId(), err, nowMs);
            });
            return new StepOutcome(statusWhenHeld, reload(job.jobId()), err);
        } catch (StaleRebuildLeaseException fenced) {
            return leaseFencedOutcome(job, fenced);
        }
    }

    private StepOutcome leaseFencedOutcome(RebuildJobRecord job, StaleRebuildLeaseException fenced) {
        LOGGER.warn("event=search_rebuild_lease_lost_fenced jobId={} instanceId={} reason={}",
                job.jobId(), instanceId, fenced.reason());
        return new StepOutcome(StepStatus.LEASE_LOST_FENCED, reload(job.jobId()), fenced.reason());
    }

    private static long effectiveSwitchAttempt(RebuildJobRecord job) {
        return Math.max(1L, job.switchAttempts());
    }

    private static String resolveStepInvalidateCacheBypassOwner(
            RebuildJobRecord job, Optional<IndexGenerationState> currentGen) {
        if (currentGen.isPresent() && currentGen.get().activeGeneration() == job.candidateGeneration()) {
            IndexGenerationState gen = currentGen.get();
            if (StringUtils.hasText(gen.activeCutoverBypassOwner())) {
                return gen.activeCutoverBypassOwner().trim();
            }
            if (gen.activeCacheInvalidationStatus() == CacheInvalidationStatus.PENDING) {
                // Upgraded SEARCH-03-R2 row: active_cutover_bypass_owner is NULL while Redis holds legacy "<alias>:g<N>".
                return SearchCacheInvalidator.cutoverBypassOwnerToken(job.aliasName(), job.candidateGeneration());
            }
        }
        return cutoverBypassOwner(job);
    }

    private static String cutoverBypassOwner(RebuildJobRecord job) {
        return SearchCacheInvalidator.cutoverBypassOwnerToken(
                job.aliasName(), job.candidateGeneration(), effectiveSwitchAttempt(job));
    }

    private void clearCutoverBypassBestEffort(String jobId, String expectedOwnerToken) {
        if (!cacheInvalidator.isCacheEnabled()) {
            return;
        }
        try {
            cacheInvalidator.clearCutoverBypassIfOwned(expectedOwnerToken);
        } catch (Exception ex) {
            LOGGER.warn("event=search_rebuild_cutover_bypass_clear_failed jobId={} owner={} error={}",
                    jobId, expectedOwnerToken, ex.getMessage());
        }
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
        LEASE_LOST_FENCED,
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
