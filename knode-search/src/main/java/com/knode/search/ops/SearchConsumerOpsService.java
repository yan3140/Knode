package com.knode.search.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.auth.UserTokenVerifier;
import com.knode.search.client.CanonicalIdParser;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.ArticleVersionBarrier;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexDocument;
import com.knode.search.consumer.persistence.SearchIndexOperation;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.consumer.stream.ContentStreamClient;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.QueryBudget;
import com.knode.search.rebuild.client.ArticleCurrentSnapshot;
import com.knode.search.rebuild.client.ContentSnapshotClient;
import com.knode.search.rebuild.client.SnapshotArticleItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SearchConsumerOpsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchConsumerOpsService.class);
    private static final Pattern REPLAY_REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("^[A-Za-z0-9:._-]{1,128}$");
    private static final Set<String> RECOVERABLE_ARTICLE_EVENT_TYPES = Set.of(
            "ArticlePublished",
            "ArticleContentChanged",
            "ArticleVisibilityChanged",
            "ArticleDeleted"
    );
    private static final int MAX_PAGE_NUM = 100_000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_IDEMPOTENCY_RACE_ATTEMPTS = 3;
    private static final String AUDIT_STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SearchOpsRepository opsRepository;
    private final SearchConsumerRepository consumerRepository;
    private final TransactionTemplate transactionTemplate;
    private final ContentStreamClient streamClient;
    private final ContentSnapshotClient snapshotClient;
    private final SearchConsumerProperties consumerProperties;
    private final SearchOpsProperties opsProperties;
    private final SearchMetrics searchMetrics;
    private final ObjectMapper objectMapper;
    private final String aliasName;
    private final Clock clock;

    public SearchConsumerOpsService(
            ObjectProvider<SearchOpsRepository> opsRepositoryProvider,
            ObjectProvider<SearchConsumerRepository> consumerRepositoryProvider,
            @Qualifier("searchConsumerTransactionTemplate") ObjectProvider<TransactionTemplate> transactionTemplateProvider,
            ObjectProvider<ContentStreamClient> streamClientProvider,
            SearchOpsProperties opsProperties,
            SearchElasticsearchProperties esProperties,
            SearchMetrics searchMetrics,
            ObjectMapper objectMapper,
            Clock clock) {
        this(
                opsRepositoryProvider,
                consumerRepositoryProvider,
                transactionTemplateProvider,
                streamClientProvider,
                emptyProvider(),
                new SearchConsumerProperties(),
                opsProperties,
                esProperties,
                searchMetrics,
                objectMapper,
                clock
        );
    }

    @Autowired
    public SearchConsumerOpsService(
            ObjectProvider<SearchOpsRepository> opsRepositoryProvider,
            ObjectProvider<SearchConsumerRepository> consumerRepositoryProvider,
            @Qualifier("searchConsumerTransactionTemplate") ObjectProvider<TransactionTemplate> transactionTemplateProvider,
            ObjectProvider<ContentStreamClient> streamClientProvider,
            ObjectProvider<ContentSnapshotClient> snapshotClientProvider,
            SearchConsumerProperties consumerProperties,
            SearchOpsProperties opsProperties,
            SearchElasticsearchProperties esProperties,
            SearchMetrics searchMetrics,
            ObjectMapper objectMapper,
            Clock clock) {
        this.opsRepository = opsRepositoryProvider.getIfAvailable();
        this.consumerRepository = consumerRepositoryProvider.getIfAvailable();
        this.transactionTemplate = transactionTemplateProvider.getIfAvailable();
        this.streamClient = streamClientProvider.getIfAvailable();
        this.snapshotClient = snapshotClientProvider == null ? null : snapshotClientProvider.getIfAvailable();
        this.consumerProperties = consumerProperties == null ? new SearchConsumerProperties() : consumerProperties;
        this.opsProperties = opsProperties == null ? new SearchOpsProperties() : opsProperties;
        this.searchMetrics = searchMetrics;
        this.objectMapper = objectMapper;
        this.aliasName = esProperties != null && StringUtils.hasText(esProperties.getIndex())
                ? esProperties.getIndex().trim()
                : "knode_local_articles_search";
        this.clock = clock;
    }

    public Map<String, Object> getSummary() {
        requireConsumerPersistence();
        long nowMs = clock.millis();
        SearchOpsRepository.OpsSummaryDbSnapshot db;
        try {
            db = opsRepository.loadSummarySnapshot(aliasName, aliasName, nowMs);
        } catch (Exception ex) {
            LOGGER.warn("event=search_ops_summary_db_failed errorType={}", ex.getClass().getSimpleName());
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE", "Search consumer database is unavailable", true, ex);
        }

        Map<String, Object> inbox = new LinkedHashMap<>();
        inbox.put("APPLIED", Long.toString(db.inboxCounts().getOrDefault("APPLIED", 0L)));
        inbox.put("STALE_IGNORED", Long.toString(db.inboxCounts().getOrDefault("STALE_IGNORED", 0L)));
        inbox.put("IGNORED", Long.toString(db.inboxCounts().getOrDefault("IGNORED", 0L)));
        inbox.put("PROCESSING", Long.toString(db.inboxCounts().getOrDefault("PROCESSING", 0L)));
        inbox.put("PROTOCOL_FAILED", Long.toString(db.inboxCounts().getOrDefault("PROTOCOL_FAILED", 0L)));

        long pending = db.taskCounts().getOrDefault("PENDING", 0L);
        long leased = db.taskCounts().getOrDefault("LEASED", 0L);
        long done = db.taskCounts().getOrDefault("DONE", 0L);
        long dead = db.taskCounts().getOrDefault("DEAD", 0L);
        long backlog = pending + leased + dead;

        Map<String, Object> indexTasks = new LinkedHashMap<>();
        indexTasks.put("PENDING", Long.toString(pending));
        indexTasks.put("LEASED", Long.toString(leased));
        indexTasks.put("PROCESSING", Long.toString(leased));
        indexTasks.put("DONE", Long.toString(done));
        indexTasks.put("SUCCEEDED", Long.toString(done));
        indexTasks.put("DEAD", Long.toString(dead));
        indexTasks.put("expiredLeased", Long.toString(db.expiredLeased()));
        indexTasks.put("backlog", Long.toString(backlog));

        Map<String, Object> cacheCompensation = new LinkedHashMap<>();
        cacheCompensation.put("taskPending", Long.toString(db.taskCachePending()));
        cacheCompensation.put("generationStatus", db.generationCacheStatus());
        cacheCompensation.put("generationAttempts", Integer.toString(db.generationCacheAttempts()));

        Map<String, Object> rebuild = new LinkedHashMap<>();
        if (db.activeRebuild() != null) {
            rebuild.put("active", true);
            rebuild.put("jobId", db.activeRebuild().jobId());
            rebuild.put("phase", db.activeRebuild().phase());
            rebuild.put("candidateGeneration", Long.toString(db.activeRebuild().candidateGeneration()));
            rebuild.put("candidatePhysicalIndex", db.activeRebuild().candidatePhysicalIndex());
            rebuild.put("snapshotItemsImported", Long.toString(db.activeRebuild().snapshotItemsImported()));
            rebuild.put("incrementalEventsApplied", Long.toString(db.activeRebuild().incrementalEventsApplied()));
        } else {
            rebuild.put("active", false);
            rebuild.put("jobId", null);
            rebuild.put("phase", null);
            rebuild.put("candidateGeneration", null);
            rebuild.put("candidatePhysicalIndex", null);
            rebuild.put("snapshotItemsImported", "0");
            rebuild.put("incrementalEventsApplied", "0");
        }
        rebuild.put("lastCompletedJobId", db.lastCompletedRebuild() == null ? null : db.lastCompletedRebuild().jobId());
        rebuild.put("lastCompletedAtMs", db.lastCompletedRebuild() == null ? null
                : Long.toString(db.lastCompletedRebuild().completedAtMs()));

        Map<String, Object> stream = buildStreamSummary();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "search");
        response.put("status", "AVAILABLE");
        response.put("alias", db.aliasName());
        response.put("activeGeneration", Long.toString(db.activeGeneration()));
        response.put("activePhysicalIndex", db.activePhysicalIndex());
        response.put("cutoverState", db.cutoverState());
        response.put("inbox", inbox);
        response.put("indexTasks", indexTasks);
        response.put("cacheCompensation", cacheCompensation);
        response.put("rebuild", rebuild);
        response.put("stream", stream);
        return response;
    }

    public Map<String, Object> listEvents(Integer rawPageNum, Integer rawPageSize, String rawStatus) {
        requireConsumerPersistence();
        int pageNum = rawPageNum == null ? 1 : rawPageNum;
        int pageSize = rawPageSize == null ? DEFAULT_PAGE_SIZE : rawPageSize;
        if (pageNum < 1 || pageNum > MAX_PAGE_NUM) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", "pageNum must be in 1.." + MAX_PAGE_NUM, false);
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", "pageSize must be in 1.." + MAX_PAGE_SIZE, false);
        }
        String normalizedStatus = normalizeStatusFilter(rawStatus);

        try {
            long activeGeneration = consumerRepository.findGenerationState(aliasName)
                    .map(IndexGenerationState::activeGeneration)
                    .orElse(SearchConsumerRepository.DEFAULT_GENERATION);
            SearchOpsRepository.EventPageResult page = opsRepository.listConsumerEvents(
                    activeGeneration, normalizedStatus, pageNum, pageSize);

            List<Map<String, Object>> rows = new ArrayList<>(page.rows().size());
            for (SearchOpsRepository.ConsumerEventRow item : page.rows()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("recordType", item.recordType());
                row.put("eventId", item.eventId());
                if (item.failureKey() != null) {
                    row.put("failureKey", item.failureKey());
                }
                if (item.indexGeneration() != null) {
                    row.put("indexGeneration", Long.toString(item.indexGeneration()));
                }
                if (item.articleId() != null) {
                    row.put("articleId", Long.toString(item.articleId()));
                }
                if (item.targetProjectionRevision() != null) {
                    row.put("targetProjectionRevision", Long.toString(item.targetProjectionRevision()));
                }
                if (item.operation() != null) {
                    row.put("operation", item.operation());
                }
                row.put("status", item.status());
                row.put("taskStatus", item.rawStatus());
                row.put("retryCount", Integer.toString(item.retryCount()));
                row.put("maxAttempts", Integer.toString(item.maxAttempts()));
                row.put("dueAtMs", item.dueAtMs() == null ? null : Long.toString(item.dueAtMs()));
                row.put("leaseUntilMs", item.leaseUntilMs() == null ? null : Long.toString(item.leaseUntilMs()));
                row.put("lastError", item.lastError());
                row.put("cacheInvalidationStatus", item.cacheInvalidationStatus());
                if (item.streamRecordId() != null) {
                    row.put("streamRecordId", item.streamRecordId());
                }
                row.put("updatedAtMs", Long.toString(item.updatedAtMs()));
                rows.add(row);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("rows", rows);
            response.put("total", Long.toString(page.total()));
            response.put("pageNum", Integer.toString(pageNum));
            response.put("pageSize", Integer.toString(pageSize));
            return response;
        } catch (InternalOpsException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("event=search_ops_events_db_failed errorType={}", ex.getClass().getSimpleName());
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE", "Search consumer database is unavailable", true, ex);
        }
    }

    public Map<String, Object> retryBatch(
            ServiceTokenVerifier.Principal caller,
            String replayRequestId,
            String operator,
            Integer requestedLimit) {
        requireConsumerPersistence();
        String validReplayId = requireValidReplayRequestId(replayRequestId);
        String validOperator = validateOptionalOperator(operator);
        int maxLimit = Math.max(1, opsProperties.getMaxBatchRetryLimit());
        int defaultLimit = Math.max(1, Math.min(maxLimit, opsProperties.getDefaultBatchRetryLimit()));
        int limit = requestedLimit == null ? defaultLimit : requestedLimit;
        if (limit < 1 || limit > maxLimit) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", "limit must be in 1.." + maxLimit, false);
        }
        String callerService = caller.service();
        String fingerprint = sha256("BATCH_RETRY\n\n" + callerService + "\n"
                + (validOperator == null ? "" : validOperator) + "\n" + limit);

        try {
            Map<String, Object> result = executeIdempotentReplay(
                    "BATCH_RETRY",
                    null,
                    validReplayId,
                    fingerprint,
                    callerService,
                    validOperator,
                    nowMs -> {
                        SearchOpsRepository.BatchRetryOutcome outcome =
                                opsRepository.requeueBatchInCurrentTx(aliasName, aliasName, limit, nowMs);

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "ACCEPTED");
                        response.put("replayRequestId", validReplayId);
                        response.put("requeued", Long.toString(outcome.requeued()));
                        response.put("skippedActiveLease", Long.toString(outcome.skippedActiveLease()));
                        response.put("cacheCompensationsExpedited", Long.toString(outcome.cacheCompensationsExpedited()));
                        response.put("callerService", callerService);
                        if (validOperator != null) {
                            response.put("operator", validOperator);
                        }
                        return new ReplayWorkResult("ACCEPTED", outcome.requeued(), response);
                    }
            );
            searchMetrics.recordOpsRetry("batch", "accepted");
            LOGGER.info("event=search_ops_batch_retry caller={} operator={} replayRequestId={} requeued={}",
                    callerService, validOperator, validReplayId, result.get("requeued"));
            return result;
        } catch (InternalOpsException ex) {
            searchMetrics.recordOpsRetry("batch", ex.httpStatus() == 409 ? "conflict" : "error");
            throw ex;
        } catch (Exception ex) {
            searchMetrics.recordOpsRetry("batch", "error");
            LOGGER.warn("event=search_ops_batch_retry_failed errorType={}", ex.getClass().getSimpleName());
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE", "Search consumer database is unavailable", true, ex);
        }
    }

    public Map<String, Object> retrySingleEvent(
            ServiceTokenVerifier.Principal caller,
            String eventId,
            String replayRequestId,
            String operator) {
        requireConsumerPersistence();
        if (eventId == null || !UserTokenVerifier.validUuid(eventId)) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT", "eventId must be a canonical lowercase UUID", false);
        }
        String validReplayId = requireValidReplayRequestId(replayRequestId);
        String validOperator = validateOptionalOperator(operator);
        String callerService = caller.service();
        String fingerprint = sha256("SINGLE_RETRY\n" + eventId + "\n" + callerService + "\n"
                + (validOperator == null ? "" : validOperator));

        try {
            // Phase 0: Fast-path check for already-committed replayRequestId (avoids unnecessary upstream calls).
            Optional<SearchOpsRepository.ReplayAuditRecord> preExistingAudit =
                    transactionTemplate.execute(s -> opsRepository.findReplayAudit(validReplayId));
            if (preExistingAudit != null && preExistingAudit.isPresent()) {
                Map<String, Object> replayResult = resolveIdempotentReplay(
                        preExistingAudit.get(), validReplayId, fingerprint);
                String outcomeTag = "NOOP".equals(replayResult.get("status")) ? "noop" : "accepted";
                searchMetrics.recordOpsRetry("single", outcomeTag);
                return replayResult;
            }

            // Phase 1: Non-transactional pre-check for legacy mis-ACKed STALE_IGNORED ARTICLE content recovery.
            // Fetches authoritative Content snapshot OUTSIDE any SQL transaction; normal stale/late events
            // skip Content calls completely.
            OptionalLong staleArticleToFetch = transactionTemplate.execute(
                    s -> findEligibleStaleIgnoredArticleForPrefetch(eventId, clock.millis()));
            ArticleCurrentSnapshot prefetchedSnapshot = null;
            if (staleArticleToFetch != null && staleArticleToFetch.isPresent()) {
                prefetchedSnapshot = fetchAuthoritativeSnapshotOutsideTx(
                        staleArticleToFetch.getAsLong(), validReplayId);
            }

            final ArticleCurrentSnapshot authoritativeSnapshot = prefetchedSnapshot;
            Map<String, Object> result = executeIdempotentReplay(
                    "SINGLE_RETRY",
                    eventId,
                    validReplayId,
                    fingerprint,
                    callerService,
                    validOperator,
                    nowMs -> executeSingleRetryInCurrentTx(
                            eventId, validReplayId, callerService, validOperator, authoritativeSnapshot, nowMs)
            );
            String outcomeTag = "NOOP".equals(result.get("status")) ? "noop" : "accepted";
            searchMetrics.recordOpsRetry("single", outcomeTag);
            LOGGER.info("event=search_ops_single_retry caller={} operator={} replayRequestId={} eventId={} status={} requeued={}",
                    callerService, validOperator, validReplayId, eventId, result.get("status"), result.get("requeued"));
            return result;
        } catch (InternalOpsException ex) {
            searchMetrics.recordOpsRetry("single", ex.httpStatus() == 409 ? "conflict" : "error");
            throw ex;
        } catch (Exception ex) {
            searchMetrics.recordOpsRetry("single", "error");
            LOGGER.warn("event=search_ops_single_retry_failed eventId={} errorType={}", eventId, ex.getClass().getSimpleName());
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE", "Search consumer database is unavailable", true, ex);
        }
    }

    private OptionalLong findEligibleStaleIgnoredArticleForPrefetch(String eventId, long nowMs) {
        if (opsRepository.hasProtocolFailureForEvent(eventId)) {
            return OptionalLong.empty();
        }
        Optional<SearchOpsRepository.InboxLookupRecord> maybeInbox = opsRepository.findInboxByEventId(eventId);
        if (maybeInbox.isEmpty()) {
            return OptionalLong.empty();
        }
        SearchOpsRepository.InboxLookupRecord inbox = maybeInbox.get();
        if (!"STALE_IGNORED".equals(inbox.status()) || !isRecoverableArticleContentEvent(inbox)) {
            return OptionalLong.empty();
        }
        String normalizedArticleId = CanonicalIdParser.normalizePositiveDecimalString(inbox.aggregateId());
        if (normalizedArticleId == null) {
            return OptionalLong.empty();
        }
        long articleId = Long.parseLong(normalizedArticleId);

        Optional<IndexGenerationState> maybeGen = consumerRepository.findGenerationState(aliasName);
        if (maybeGen.isPresent() && !maybeGen.get().isNormal()) {
            return OptionalLong.empty();
        }
        long activeGen = maybeGen.map(IndexGenerationState::activeGeneration)
                .orElse(SearchConsumerRepository.DEFAULT_GENERATION);

        Optional<SearchIndexTaskRecord> maybeTask = consumerRepository.findIndexTask(activeGen, articleId);
        if (maybeTask.isPresent()
                && maybeTask.get().status() == SearchIndexTaskStatus.LEASED
                && maybeTask.get().leaseUntilMs() != null
                && maybeTask.get().leaseUntilMs() > nowMs) {
            return OptionalLong.empty();
        }

        Optional<ArticleProjectionRecord> maybeProj = consumerRepository.findProjection(activeGen, articleId);
        long projRev = maybeProj.map(ArticleProjectionRecord::contentRevision).orElse(0L);
        ArticleVisibility projVis = maybeProj.map(ArticleProjectionRecord::visibility).orElse(ArticleVisibility.NONE);

        ArticleVersionBarrier effectiveBarrier = consumerRepository.findVersionBarrier(articleId).orElse(null);
        if (effectiveBarrier != null && isBarrierAheadOfProjection(effectiveBarrier, projRev, projVis)) {
            effectiveBarrier = consumerRepository.resolveEffectiveVersionBarrier(
                    activeGen, articleId, effectiveBarrier, false, nowMs);
        }
        if (!isInboxAheadOfEffectiveFloor(inbox, projRev, projVis, effectiveBarrier)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(articleId);
    }

    private ArticleCurrentSnapshot fetchAuthoritativeSnapshotOutsideTx(long articleId, String validReplayId) {
        if (snapshotClient == null) {
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                    "Content snapshot client is not configured for STALE_IGNORED recovery", true);
        }
        try {
            QueryBudget budget = QueryBudget.start(Duration.ofMillis(Math.max(500L, opsProperties.getSyncTimeoutMs())));
            Map<Long, ArticleCurrentSnapshot> batch = snapshotClient.fetchCurrentSnapshotsBatch(
                    List.of(articleId), budget, "ops-recover-" + validReplayId);
            ArticleCurrentSnapshot snapshot = batch == null ? null : batch.get(articleId);
            if (snapshot == null) {
                throw new IllegalStateException("Content snapshots:batch omitted requested articleId " + articleId);
            }
            return snapshot;
        } catch (InternalOpsException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.warn("event=search_ops_stale_recovery_upstream_failed articleId={} replayRequestId={} errorType={}",
                    articleId, validReplayId, ex.getClass().getSimpleName());
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                    "Upstream content snapshot service unavailable for STALE_IGNORED recovery", true, ex);
        }
    }

    private ReplayWorkResult executeSingleRetryInCurrentTx(
            String eventId,
            String validReplayId,
            String callerService,
            String validOperator,
            ArticleCurrentSnapshot prefetchedSnapshot,
            long nowMs) {
        if (opsRepository.hasProtocolFailureForEvent(eventId)) {
            throw new InternalOpsException(409, "STATE_CONFLICT",
                    "Poison/protocol-failed event cannot be replayed by search consumer", false);
        }
        SearchOpsRepository.InboxLookupRecord preInbox = opsRepository.findInboxByEventId(eventId)
                .orElseThrow(() -> new InternalOpsException(404, "RESOURCE_NOT_FOUND",
                        "Consumer event not found: " + eventId, false));

        if ("IGNORED".equals(preInbox.status())) {
            return buildSingleRetryWorkResult(
                    validReplayId, eventId, null, callerService, validOperator,
                    new SearchOpsRepository.SingleRetryOutcome("NOOP", 0L, "EVENT_IGNORED"),
                    null, null, null);
        }

        // Lock search_index_generation FIRST to serialize cleanly with cutover and concurrent retries,
        // then perform current-read lock on search_event_inbox (critical under MySQL REPEATABLE READ).
        IndexGenerationState gen = consumerRepository.ensureAndLockGeneration(aliasName, aliasName, nowMs);
        if (!gen.isNormal()) {
            throw new InternalOpsException(409, "STATE_CONFLICT",
                    "Alias cutover is in progress (" + gen.cutoverState() + "); retry deferred", true);
        }
        long lockNowMs = Math.max(nowMs, clock.millis());

        SearchOpsRepository.InboxLookupRecord inbox = opsRepository.lockInboxByEventIdForUpdate(eventId)
                .orElseThrow(() -> new InternalOpsException(404, "RESOURCE_NOT_FOUND",
                        "Consumer event not found: " + eventId, false));

        if ("IGNORED".equals(inbox.status())) {
            return buildSingleRetryWorkResult(
                    validReplayId, eventId, null, callerService, validOperator,
                    new SearchOpsRepository.SingleRetryOutcome("NOOP", 0L, "EVENT_IGNORED"),
                    null, null, null);
        }

        if ("STALE_IGNORED".equals(inbox.status())) {
            return recoverStaleIgnoredInCurrentTx(
                    gen, inbox, validReplayId, callerService, validOperator, prefetchedSnapshot, lockNowMs);
        }

        String normalizedArticleId = CanonicalIdParser.normalizePositiveDecimalString(inbox.aggregateId());
        if (normalizedArticleId == null) {
            throw new InternalOpsException(409, "STATE_CONFLICT",
                    "Event aggregateId is not a valid articleId", false);
        }
        SearchOpsRepository.SingleRetryOutcome outcome = opsRepository.requeueSingleArticleInCurrentTx(
                aliasName, aliasName, Long.parseLong(normalizedArticleId), lockNowMs);
        return buildSingleRetryWorkResult(
                validReplayId, eventId, normalizedArticleId, callerService, validOperator,
                outcome, null, null, null);
    }

    private ReplayWorkResult recoverStaleIgnoredInCurrentTx(
            IndexGenerationState gen,
            SearchOpsRepository.InboxLookupRecord inbox,
            String validReplayId,
            String callerService,
            String validOperator,
            ArticleCurrentSnapshot authoritative,
            long nowMs) {
        if (!isRecoverableArticleContentEvent(inbox)) {
            return buildSingleRetryWorkResult(
                    validReplayId, inbox.eventId(), null, callerService, validOperator,
                    new SearchOpsRepository.SingleRetryOutcome("NOOP", 0L, "EVENT_STALE_IGNORED"),
                    null, null, null);
        }
        String normalizedArticleId = CanonicalIdParser.normalizePositiveDecimalString(inbox.aggregateId());
        if (normalizedArticleId == null) {
            throw new InternalOpsException(409, "STATE_CONFLICT",
                    "Event aggregateId is not a valid articleId", false);
        }
        long articleId = Long.parseLong(normalizedArticleId);
        long activeGen = gen.activeGeneration();

        // Lock order matches ingestion & admin sync: barrier -> projection -> index_task.
        ArticleVersionBarrier barrier = consumerRepository.ensureAndLockVersionBarrier(articleId, nowMs);
        ArticleProjectionRecord current = consumerRepository.ensureAndLockProjection(activeGen, articleId, nowMs);
        ArticleVersionBarrier effectiveBarrier = barrier;
        boolean barrierNeedsRepair = isBarrierAheadOfProjection(
                barrier, current.contentRevision(), current.visibility());
        if (barrierNeedsRepair) {
            effectiveBarrier = consumerRepository.resolveEffectiveVersionBarrier(
                    activeGen, articleId, barrier, false, nowMs);
        }

        if (!isInboxAheadOfEffectiveFloor(inbox, current.contentRevision(), current.visibility(), effectiveBarrier)) {
            return buildSingleRetryWorkResult(
                    validReplayId, inbox.eventId(), normalizedArticleId, callerService, validOperator,
                    new SearchOpsRepository.SingleRetryOutcome("NOOP", 0L, "EVENT_STALE_IGNORED"),
                    null, null, null);
        }

        Optional<SearchIndexTaskRecord> lockedTask = opsRepository.lockIndexTaskForUpdate(activeGen, articleId);
        if (lockedTask.isPresent()
                && lockedTask.get().status() == SearchIndexTaskStatus.LEASED
                && lockedTask.get().leaseUntilMs() != null
                && lockedTask.get().leaseUntilMs() > nowMs) {
            throw new InternalOpsException(409, "STATE_CONFLICT",
                    "Index task for article " + articleId + " holds an active worker lease", true);
        }

        if (authoritative == null) {
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                    "Authoritative content snapshot was not prefetched; retry request", true);
        }

        long effectiveFloor = Math.max(current.contentRevision(), effectiveBarrier.maxContentRevision());
        long requiredAuthoritativeRevision = Math.max(effectiveFloor, inbox.sourceRevision());
        ArticleVisibility inboxVis = SearchConsumerRepository.deriveInboxArticleVisibility(
                inbox.eventType(), inbox.visibility());

        if (authoritative.isReadablePublic()) {
            SnapshotArticleItem item = authoritative.snapshot();
            long rev = item.sourceRevision();
            boolean lagsBehindHiddenState =
                    (rev == effectiveBarrier.maxContentRevision()
                            && SearchConsumerRepository.visibilityRank(effectiveBarrier.lastVisibility())
                            > SearchConsumerRepository.visibilityRank(ArticleVisibility.PUBLIC))
                    || (rev == current.contentRevision()
                            && SearchConsumerRepository.visibilityRank(current.visibility())
                            > SearchConsumerRepository.visibilityRank(ArticleVisibility.PUBLIC))
                    || (rev == inbox.sourceRevision()
                            && SearchConsumerRepository.visibilityRank(inboxVis)
                            > SearchConsumerRepository.visibilityRank(ArticleVisibility.PUBLIC));
            if (rev < requiredAuthoritativeRevision || lagsBehindHiddenState) {
                throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                        "Authoritative PUBLIC snapshot revision " + rev
                                + " lags behind required revision " + requiredAuthoritativeRevision
                                + " for article " + articleId + "; retry request",
                        true);
            }
            if (barrierNeedsRepair) {
                effectiveBarrier = consumerRepository.resolveEffectiveVersionBarrier(
                        activeGen, articleId, barrier, true, nowMs);
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
            consumerRepository.updateInboxStatus(inbox.eventId(), "APPLIED", nowMs);

            return buildSingleRetryWorkResult(
                    validReplayId, inbox.eventId(), normalizedArticleId, callerService, validOperator,
                    new SearchOpsRepository.SingleRetryOutcome("ACCEPTED", 1L, "STALE_IGNORED_RECOVERED_FROM_CONTENT"),
                    "UPSERT_ENQUEUED", Long.toString(rev), "PUBLIC");
        }

        if (authoritative.isMissing()) {
            if (barrierNeedsRepair) {
                effectiveBarrier = consumerRepository.resolveEffectiveVersionBarrier(
                        activeGen, articleId, barrier, true, nowMs);
            }
            long tombstoneRev = Math.max(requiredAuthoritativeRevision, 1L);
            long nextProjRev = current.projectionRevision() + 1L;
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
            consumerRepository.updateInboxStatus(inbox.eventId(), "APPLIED", nowMs);

            return buildSingleRetryWorkResult(
                    validReplayId, inbox.eventId(), normalizedArticleId, callerService, validOperator,
                    new SearchOpsRepository.SingleRetryOutcome("ACCEPTED", 1L, "STALE_IGNORED_RECOVERED_FROM_CONTENT"),
                    "TOMBSTONE_ENQUEUED", Long.toString(tombstoneRev), "MISSING");
        }

        // WITHDRAWN, DELETED, or DRAFT (all non-public with known numeric authoritative sourceRevision)
        long rev = authoritative.sourceRevision();
        ArticleVisibility hiddenVis = "DELETED".equals(authoritative.visibility())
                ? ArticleVisibility.DELETED
                : ArticleVisibility.WITHDRAWN;
        int hiddenRank = SearchConsumerRepository.visibilityRank(hiddenVis);
        boolean hiddenLagsBehindHigherVisibility =
                (rev == effectiveBarrier.maxContentRevision()
                        && hiddenRank < SearchConsumerRepository.visibilityRank(effectiveBarrier.lastVisibility()))
                || (rev == current.contentRevision()
                        && hiddenRank < SearchConsumerRepository.visibilityRank(current.visibility()))
                || (rev == inbox.sourceRevision()
                        && hiddenRank < SearchConsumerRepository.visibilityRank(inboxVis));
        if (rev <= 0L || rev < requiredAuthoritativeRevision || hiddenLagsBehindHigherVisibility) {
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                    "Authoritative hidden snapshot (" + authoritative.visibility() + " v" + rev
                            + ") lags behind required revision " + requiredAuthoritativeRevision
                            + " for article " + articleId + "; retry request",
                    true);
        }
        if (barrierNeedsRepair) {
            effectiveBarrier = consumerRepository.resolveEffectiveVersionBarrier(
                    activeGen, articleId, barrier, true, nowMs);
        }
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
        consumerRepository.updateInboxStatus(inbox.eventId(), "APPLIED", nowMs);

        return buildSingleRetryWorkResult(
                validReplayId, inbox.eventId(), normalizedArticleId, callerService, validOperator,
                new SearchOpsRepository.SingleRetryOutcome("ACCEPTED", 1L, "STALE_IGNORED_RECOVERED_FROM_CONTENT"),
                "TOMBSTONE_ENQUEUED", Long.toString(rev), authoritative.visibility());
    }

    private ReplayWorkResult buildSingleRetryWorkResult(
            String validReplayId,
            String eventId,
            String articleIdStr,
            String callerService,
            String validOperator,
            SearchOpsRepository.SingleRetryOutcome outcome,
            String recoveryAction,
            String authoritativeRevision,
            String authoritativeVisibility) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", outcome.status());
        response.put("replayRequestId", validReplayId);
        response.put("eventId", eventId);
        if (articleIdStr != null) {
            response.put("articleId", articleIdStr);
        }
        response.put("requeued", Long.toString(outcome.requeued()));
        response.put("reason", outcome.reason());
        if (recoveryAction != null) {
            response.put("recoveryAction", recoveryAction);
        }
        if (authoritativeRevision != null) {
            response.put("authoritativeRevision", authoritativeRevision);
        }
        if (authoritativeVisibility != null) {
            response.put("authoritativeVisibility", authoritativeVisibility);
        }
        response.put("callerService", callerService);
        if (validOperator != null) {
            response.put("operator", validOperator);
        }
        return new ReplayWorkResult(outcome.status(), outcome.requeued(), response);
    }

    private static boolean isRecoverableArticleContentEvent(SearchOpsRepository.InboxLookupRecord inbox) {
        return inbox != null
                && inbox.aggregateType() != null
                && "ARTICLE".equalsIgnoreCase(inbox.aggregateType().trim())
                && inbox.eventType() != null
                && RECOVERABLE_ARTICLE_EVENT_TYPES.contains(inbox.eventType())
                && inbox.sourceRevision() > 0L;
    }

    private static boolean isBarrierAheadOfProjection(
            ArticleVersionBarrier barrier, long projRev, ArticleVisibility projVis) {
        if (barrier == null || barrier.maxContentRevision() <= 0L) {
            return false;
        }
        return barrier.maxContentRevision() > projRev
                || (barrier.maxContentRevision() == projRev
                && SearchConsumerRepository.visibilityRank(barrier.lastVisibility())
                > SearchConsumerRepository.visibilityRank(projVis));
    }

    private static boolean isInboxAheadOfEffectiveFloor(
            SearchOpsRepository.InboxLookupRecord inbox,
            long projRev,
            ArticleVisibility projVis,
            ArticleVersionBarrier effectiveBarrier) {
        ArticleVisibility inboxVis = SearchConsumerRepository.deriveInboxArticleVisibility(
                inbox.eventType(), inbox.visibility());
        int inboxRank = SearchConsumerRepository.visibilityRank(inboxVis);

        boolean aheadOfProjection = inbox.sourceRevision() > projRev
                || (inbox.sourceRevision() == projRev && inboxRank > SearchConsumerRepository.visibilityRank(projVis));
        if (!aheadOfProjection) {
            return false;
        }

        long barrierRev = effectiveBarrier == null ? 0L : effectiveBarrier.maxContentRevision();
        ArticleVisibility barrierVis = effectiveBarrier == null
                ? ArticleVisibility.NONE
                : effectiveBarrier.lastVisibility();
        if (barrierRev <= 0L) {
            return true;
        }
        if (inbox.sourceRevision() > barrierRev) {
            return true;
        }
        if (inbox.sourceRevision() < barrierRev) {
            return false;
        }
        return inboxRank >= SearchConsumerRepository.visibilityRank(barrierVis);
    }

    public SearchOpsRepository.BatchRetryOutcome retryBatchForAdmin(String adminUserId, Integer requestedLimit) {
        if (opsRepository == null || transactionTemplate == null) {
            throw new AdminDependencyUnavailableException("Search consumer database is not enabled");
        }
        int maxLimit = Math.max(1, opsProperties.getMaxBatchRetryLimit());
        int defaultLimit = Math.max(1, Math.min(maxLimit, opsProperties.getDefaultBatchRetryLimit()));
        int limit = requestedLimit == null ? defaultLimit : Math.max(1, Math.min(maxLimit, requestedLimit));
        try {
            SearchOpsRepository.BatchRetryOutcome outcome = transactionTemplate.execute(status ->
                    opsRepository.requeueBatchInCurrentTx(aliasName, aliasName, limit, clock.millis()));
            searchMetrics.recordOpsRetry("admin", "accepted");
            LOGGER.info("event=search_admin_retry adminUserId={} requeued={} skippedActiveLease={} cacheExpedited={}",
                    adminUserId, outcome.requeued(), outcome.skippedActiveLease(), outcome.cacheCompensationsExpedited());
            return outcome;
        } catch (InternalOpsException ex) {
            searchMetrics.recordOpsRetry("admin", "conflict");
            throw new AdminDependencyUnavailableException(ex.getMessage(), ex);
        } catch (Exception ex) {
            searchMetrics.recordOpsRetry("admin", "error");
            throw new AdminDependencyUnavailableException("Failed to requeue search index tasks", ex);
        }
    }

    private Map<String, Object> buildStreamSummary() {
        Map<String, Object> stream = new LinkedHashMap<>();
        if (streamClient == null) {
            stream.put("status", "UNCONFIGURED");
            stream.put("streamKey", null);
            stream.put("consumerGroup", null);
            stream.put("pendingCount", null);
            return stream;
        }
        stream.put("streamKey", streamClient.streamKey());
        stream.put("consumerGroup", streamClient.groupName());
        try {
            OptionalLong pendingCount = streamClient.queryPendingCount();
            if (pendingCount.isPresent()) {
                stream.put("status", "AVAILABLE");
                stream.put("pendingCount", Long.toString(pendingCount.getAsLong()));
            } else {
                stream.put("status", "UNAVAILABLE");
                stream.put("pendingCount", null);
            }
        } catch (Exception ex) {
            LOGGER.warn("event=search_ops_stream_summary_failed errorType={}", ex.getClass().getSimpleName());
            stream.put("status", "UNAVAILABLE");
            stream.put("pendingCount", null);
        }
        return stream;
    }

    private Map<String, Object> executeIdempotentReplay(
            String operationType,
            String targetEventId,
            String validReplayId,
            String fingerprint,
            String callerService,
            String validOperator,
            ReplayWork work) {
        for (int attempt = 0; attempt < MAX_IDEMPOTENCY_RACE_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    // Non-locking read first avoids MySQL InnoDB REPEATABLE READ gap-lock deadlocks
                    // when concurrent transactions check a nonexistent replay_request_id before INSERT.
                    Optional<SearchOpsRepository.ReplayAuditRecord> existing =
                            opsRepository.findReplayAudit(validReplayId);
                    if (existing.isPresent()) {
                        return resolveIdempotentReplay(existing.get(), validReplayId, fingerprint);
                    }

                    long nowMs = clock.millis();
                    // Reserve the replayRequestId primary key BEFORE mutating any index task row.
                    // Concurrent transactions with the same replayRequestId serialize on the DB PK lock;
                    // the losing transaction immediately rolls back without touching search_index_task.
                    boolean reserved = opsRepository.insertReplayAudit(new SearchOpsRepository.ReplayAuditRecord(
                            validReplayId,
                            operationType,
                            targetEventId,
                            fingerprint,
                            callerService,
                            validOperator,
                            AUDIT_STATUS_IN_PROGRESS,
                            0L,
                            "{}",
                            nowMs
                    ));
                    if (!reserved) {
                        throw new ReplayAuditRaceException();
                    }

                    ReplayWorkResult workResult = work.execute(nowMs);
                    String responseJson = serializeJson(workResult.response());
                    opsRepository.completeReplayAuditInCurrentTx(
                            validReplayId,
                            workResult.responseStatus(),
                            workResult.requeuedCount(),
                            responseJson
                    );
                    return workResult.response();
                });
            } catch (ReplayAuditRaceException race) {
                // Losing transaction has been cleanly rolled back; read winner's committed audit row
                // in a fresh transaction so snapshot-isolated databases (e.g. MySQL REPEATABLE READ) observe it.
                Optional<SearchOpsRepository.ReplayAuditRecord> committed =
                        transactionTemplate.execute(s -> opsRepository.findReplayAudit(validReplayId));
                if (committed != null && committed.isPresent()) {
                    return resolveIdempotentReplay(committed.get(), validReplayId, fingerprint);
                }
                // Winner rolled back before our fresh read; retry bounded loop to claim cleanly.
            }
        }
        throw new InternalOpsException(409, "IDEMPOTENCY_CONFLICT", "Concurrent replayRequestId conflict", false);
    }

    private Map<String, Object> resolveIdempotentReplay(
            SearchOpsRepository.ReplayAuditRecord existing,
            String expectedReplayRequestId,
            String expectedFingerprint) {
        if (!expectedReplayRequestId.equals(existing.replayRequestId())) {
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                    "Stored replay audit key does not match exact replayRequestId", true);
        }
        if (!expectedFingerprint.equals(existing.requestFingerprint())) {
            throw new InternalOpsException(409, "IDEMPOTENCY_CONFLICT",
                    "replayRequestId was already used with different request parameters", false);
        }
        if (AUDIT_STATUS_IN_PROGRESS.equals(existing.responseStatus())) {
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                    "Replay request is still in progress; retry shortly", true);
        }
        try {
            return objectMapper.readValue(existing.responseJson(), MAP_TYPE);
        } catch (Exception ex) {
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                    "Stored replay audit response could not be decoded", true, ex);
        }
    }

    @FunctionalInterface
    private interface ReplayWork {
        ReplayWorkResult execute(long nowMs);
    }

    private record ReplayWorkResult(String responseStatus, long requeuedCount, Map<String, Object> response) {
    }

    private static final class ReplayAuditRaceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private void requireConsumerPersistence() {
        if (opsRepository == null || consumerRepository == null || transactionTemplate == null) {
            throw new InternalOpsException(503, "DEPENDENCY_UNAVAILABLE",
                    "Search consumer persistence is not enabled", true);
        }
    }

    private static String normalizeStatusFilter(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        String upper = rawStatus.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "PENDING" -> "PENDING";
            case "LEASED", "PROCESSING" -> "LEASED";
            case "DONE", "SUCCEEDED" -> "DONE";
            case "FAILED" -> "FAILED";
            case "DEAD" -> "DEAD";
            case "PROTOCOL_FAILED" -> "PROTOCOL_FAILED";
            case "STALE_IGNORED" -> "STALE_IGNORED";
            default -> throw new InternalOpsException(400, "INVALID_ARGUMENT",
                    "Unsupported status filter: " + rawStatus, false);
        };
    }

    private static String requireValidReplayRequestId(String replayRequestId) {
        if (replayRequestId == null || !REPLAY_REQUEST_ID_PATTERN.matcher(replayRequestId).matches()) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT",
                    "replayRequestId is required and must match [A-Za-z0-9._-]{1,64}", false);
        }
        return replayRequestId;
    }

    private static String validateOptionalOperator(String operator) {
        if (operator == null) {
            return null;
        }
        if (!OPERATOR_PATTERN.matcher(operator).matches()) {
            throw new InternalOpsException(400, "INVALID_ARGUMENT",
                    "operator must match [A-Za-z0-9:._-]{1,128}", false);
        }
        return operator;
    }

    private String serializeDocument(SearchIndexDocument doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize SearchIndexDocument", ex);
        }
    }

    private String serializeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize ops response JSON", ex);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) throws BeansException {
                return null;
            }

            @Override
            public T getIfAvailable() throws BeansException {
                return null;
            }

            @Override
            public T getIfUnique() throws BeansException {
                return null;
            }

            @Override
            public T getObject() throws BeansException {
                return null;
            }
        };
    }
}
