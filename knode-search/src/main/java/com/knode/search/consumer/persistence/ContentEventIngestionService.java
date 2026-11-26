package com.knode.search.consumer.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.client.CanonicalIdParser;
import com.knode.search.client.ContentVisibilityClient;
import com.knode.search.client.VisibilityDecision;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.event.ContentEventParser;
import com.knode.search.consumer.event.ParsedContentEvent;
import com.knode.search.consumer.event.ProtocolFailure;
import com.knode.search.model.QueryBudget;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
public class ContentEventIngestionService {
    private static final long VISIBILITY_GUARD_BUDGET_MS = 3000L;

    private final ContentEventParser eventParser;
    private final SearchConsumerRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final SearchConsumerProperties properties;
    private final String aliasName;
    private final String defaultPhysicalIndex;
    private final ContentVisibilityClient visibilityClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ContentEventIngestionService(
            ContentEventParser eventParser,
            SearchConsumerRepository repository,
            @Qualifier("searchConsumerTransactionTemplate") TransactionTemplate transactionTemplate,
            SearchConsumerProperties properties,
            SearchElasticsearchProperties esProperties,
            ObjectProvider<ContentVisibilityClient> visibilityClientProvider,
            ObjectMapper objectMapper,
            Clock clock) {
        this(
                eventParser,
                repository,
                transactionTemplate,
                properties,
                esProperties != null && StringUtils.hasText(esProperties.getIndex())
                        ? esProperties.getIndex().trim()
                        : "knode_local_articles_search",
                visibilityClientProvider == null ? null : visibilityClientProvider.getIfAvailable(),
                objectMapper,
                clock
        );
    }

    public ContentEventIngestionService(
            ContentEventParser eventParser,
            SearchConsumerRepository repository,
            TransactionTemplate transactionTemplate,
            SearchConsumerProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this(eventParser, repository, transactionTemplate, properties, "default", "", null, objectMapper, clock);
    }

    public ContentEventIngestionService(
            ContentEventParser eventParser,
            SearchConsumerRepository repository,
            TransactionTemplate transactionTemplate,
            SearchConsumerProperties properties,
            String aliasName,
            ContentVisibilityClient visibilityClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.eventParser = eventParser;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.aliasName = StringUtils.hasText(aliasName) ? aliasName.trim() : "knode_local_articles_search";
        this.defaultPhysicalIndex = this.aliasName;
        this.visibilityClient = visibilityClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private ContentEventIngestionService(
            ContentEventParser eventParser,
            SearchConsumerRepository repository,
            TransactionTemplate transactionTemplate,
            SearchConsumerProperties properties,
            String aliasName,
            String defaultPhysicalIndex,
            ContentVisibilityClient visibilityClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.eventParser = eventParser;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.aliasName = aliasName;
        this.defaultPhysicalIndex = defaultPhysicalIndex;
        this.visibilityClient = visibilityClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean isCutoverPaused() {
        Optional<IndexGenerationState> state = repository.findGenerationState(aliasName);
        return state.isPresent() && !state.get().isNormal();
    }

    public IngestionOutcome ingestStreamMessage(String streamRecordId, Map<String, String> streamFields) {
        ContentEventParser.ParseResult parsed = eventParser.parseStreamFields(streamFields);
        if (!parsed.isSuccess()) {
            return recordProtocolFailure(streamRecordId, parsed.failure());
        }
        return ingestParsedEvent(streamRecordId, parsed.event());
    }

    public IngestionOutcome recordProtocolFailure(String streamRecordId, ProtocolFailure failure) {
        return transactionTemplate.execute(status -> {
            long nowMs = clock.millis();
            repository.insertFailureIfAbsent(failure, streamRecordId, nowMs);
            return IngestionOutcome.PROTOCOL_FAILED_RECORDED;
        });
    }

    public IngestionOutcome ingestParsedEvent(String streamRecordId, ParsedContentEvent event) {
        VisibilityDecision preflightDecision = null;
        if (event instanceof ParsedContentEvent.PublicArticleEvent publicEvent) {
            preflightDecision = checkColdArticleVisibilityOutsideTxIfNeeded(publicEvent);
        }
        final VisibilityDecision resolvedDecision = preflightDecision;

        return transactionTemplate.execute(status -> {
            long nowMs = clock.millis();
            IndexGenerationState generation = repository.ensureAndLockGeneration(aliasName, defaultPhysicalIndex, nowMs);
            if (!generation.isNormal()) {
                throw new CutoverPausedException(aliasName, generation.cutoverState());
            }

            String initialInboxStatus = (event instanceof ParsedContentEvent.IgnoredNonSearchEvent)
                    ? "IGNORED"
                    : "PROCESSING";
            boolean inserted = repository.insertInboxIfAbsent(event, streamRecordId, initialInboxStatus, nowMs);
            if (!inserted) {
                return IngestionOutcome.DUPLICATE_SKIPPED;
            }
            if (event instanceof ParsedContentEvent.IgnoredNonSearchEvent) {
                return IngestionOutcome.IGNORED_NON_SEARCH;
            }
            if (event instanceof ParsedContentEvent.PublicArticleEvent publicEvent) {
                return applyPublicArticle(generation, publicEvent, resolvedDecision, nowMs);
            }
            if (event instanceof ParsedContentEvent.HiddenArticleEvent hiddenEvent) {
                return applyHiddenArticle(generation, hiddenEvent, nowMs);
            }
            if (event instanceof ParsedContentEvent.CounterChangedEvent counterEvent) {
                return applyCounterChanged(generation, counterEvent, nowMs);
            }
            throw new IllegalStateException("Unhandled event subtype: " + event.getClass().getName());
        });
    }

    private VisibilityDecision checkColdArticleVisibilityOutsideTxIfNeeded(
            ParsedContentEvent.PublicArticleEvent publicEvent) {
        Optional<IndexGenerationState> maybeGen = repository.findGenerationState(aliasName);
        if (maybeGen.isEmpty() || !maybeGen.get().requireColdVisibilityGuard()) {
            return null;
        }
        IndexGenerationState gen = maybeGen.get();
        if (!gen.isNormal()) {
            throw new CutoverPausedException(aliasName, gen.cutoverState());
        }
        if (repository.findInboxStatus(publicEvent.eventId()).isPresent()) {
            return null;
        }
        Optional<ArticleProjectionRecord> existingProj =
                repository.findProjection(gen.activeGeneration(), publicEvent.articleId());
        if (existingProj.isPresent() && existingProj.get().hasContent()) {
            return null;
        }
        Optional<ArticleVersionBarrier> barrier = repository.findVersionBarrier(publicEvent.articleId());
        if (barrier.isPresent() && barrier.get().maxContentRevision() >= publicEvent.sourceRevision()) {
            return null;
        }
        if (visibilityClient == null) {
            throw new IllegalStateException(
                    "ContentVisibilityClient is required when post-rebuild cold-article visibility guard is active");
        }
        String idStr = Long.toString(publicEvent.articleId());
        Map<String, VisibilityDecision> decisions = visibilityClient.checkPublicSearch(
                List.of(idStr),
                QueryBudget.start(Duration.ofMillis(VISIBILITY_GUARD_BUDGET_MS)),
                "search-cold-guard-" + publicEvent.eventId()
        );
        VisibilityDecision decision = decisions == null ? null : decisions.get(idStr);
        if (decision == null) {
            throw new IllegalStateException("Content visibility guard returned missing decision for article " + idStr);
        }
        return decision;
    }

    private IngestionOutcome applyPublicArticle(
            IndexGenerationState generation,
            ParsedContentEvent.PublicArticleEvent event,
            VisibilityDecision preflightDecision,
            long nowMs) {
        long gen = generation.activeGeneration();
        ArticleVersionBarrier barrier = repository.ensureAndLockVersionBarrier(event.articleId(), nowMs);
        ArticleProjectionRecord current = repository.ensureAndLockProjection(gen, event.articleId(), nowMs);

        long effectiveFloor = Math.max(current.contentRevision(), barrier.maxContentRevision());
        if (event.sourceRevision() <= effectiveFloor) {
            repository.updateInboxStatus(event.eventId(), "STALE_IGNORED", nowMs);
            return IngestionOutcome.STALE_IGNORED;
        }

        if (generation.requireColdVisibilityGuard() && !current.hasContent()) {
            if (preflightDecision == null) {
                throw new IllegalStateException(
                        "Cold-article visibility guard became active concurrently; retry ingestion");
            }
            // Step 1: Verify authoritative sourceRevision is trustworthy and at least covers event.sourceRevision()
            // BEFORE acting on visibility. A missing, invalid, or lagging authoritative revision (e.g., WITHDRAWN v6
            // for an incoming PUBLIC v7) must roll back and remain retryable without XACK, inbox commit, or barrier update.
            Long authoritativeRev = parsePositiveLongOrNull(preflightDecision.sourceRevision());
            if (authoritativeRev == null) {
                throw new IllegalStateException(
                        "Cold-article authoritative sourceRevision missing or invalid for article "
                                + event.articleId() + " (visibility=" + preflightDecision.visibility()
                                + ", decision=" + preflightDecision.decision() + ")");
            }
            if (authoritativeRev < event.sourceRevision()) {
                throw new IllegalStateException(
                        "Cold-article authoritative sourceRevision " + authoritativeRev
                                + " behind event sourceRevision " + event.sourceRevision()
                                + " for article " + event.articleId()
                                + " (visibility=" + preflightDecision.visibility()
                                + ", decision=" + preflightDecision.decision() + ")");
            }
            // Step 2: authoritativeRev >= event.sourceRevision() is confirmed.
            if (!preflightDecision.isReadablePublic()) {
                ArticleVisibility barrierVisibility = "WITHDRAWN".equalsIgnoreCase(preflightDecision.visibility())
                        ? ArticleVisibility.WITHDRAWN
                        : ArticleVisibility.DELETED;
                repository.advanceVersionBarrierIfHigher(event.articleId(), authoritativeRev, barrierVisibility, nowMs);
                ArticleProjectionRecord hiddenBarrierProj = new ArticleProjectionRecord(
                        event.articleId(),
                        true,
                        authoritativeRev,
                        barrierVisibility,
                        current.counterRevision(),
                        current.viewCount(),
                        current.projectionRevision(),
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
                repository.updateProjection(gen, hiddenBarrierProj);
                repository.updateInboxStatus(event.eventId(), "STALE_IGNORED", nowMs);
                return IngestionOutcome.STALE_IGNORED;
            }
            // Step 3: Authoritative visibility is PUBLIC/READABLE with authoritativeRev >= event.sourceRevision().
            if (event.sourceRevision() < authoritativeRev) {
                // Late stale public event: ignore without advancing projection or version barrier to authoritativeRev
                // so the real authoritative revision event can still be admitted and indexed when it arrives.
                repository.updateInboxStatus(event.eventId(), "STALE_IGNORED", nowMs);
                return IngestionOutcome.STALE_IGNORED;
            }
        }

        long mergedCounterRevision;
        long mergedViewCount;
        if (event.counterRevision() > current.counterRevision()) {
            mergedCounterRevision = event.counterRevision();
            mergedViewCount = event.viewCount();
        } else if (!current.hasContent() && current.counterRevision() == 0L && event.counterRevision() == 0L) {
            mergedCounterRevision = 0L;
            mergedViewCount = event.viewCount();
        } else {
            mergedCounterRevision = current.counterRevision();
            mergedViewCount = current.viewCount();
        }

        long nextProjectionRevision = current.projectionRevision() + 1L;
        ArticleProjectionRecord updated = new ArticleProjectionRecord(
                event.articleId(),
                true,
                event.sourceRevision(),
                ArticleVisibility.PUBLIC,
                mergedCounterRevision,
                mergedViewCount,
                nextProjectionRevision,
                event.title(),
                event.summary(),
                event.content(),
                event.categoryId(),
                event.categoryName(),
                event.tagNames(),
                event.thumbnail(),
                event.isTop(),
                event.createTimeMs(),
                nowMs
        );
        repository.updateProjection(gen, updated);
        repository.advanceVersionBarrierIfHigher(event.articleId(), event.sourceRevision(), ArticleVisibility.PUBLIC, nowMs);

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
        repository.upsertPendingIndexTask(
                gen,
                generation.activePhysicalIndex(),
                updated.articleId(),
                nextProjectionRevision,
                SearchIndexOperation.UPSERT,
                serializeDocument(doc),
                properties.getIndexer().getMaxAttempts(),
                nowMs
        );
        repository.updateInboxStatus(event.eventId(), "APPLIED", nowMs);
        return IngestionOutcome.APPLIED;
    }

    private IngestionOutcome applyHiddenArticle(
            IndexGenerationState generation,
            ParsedContentEvent.HiddenArticleEvent event,
            long nowMs) {
        long gen = generation.activeGeneration();
        ArticleVersionBarrier barrier = repository.ensureAndLockVersionBarrier(event.articleId(), nowMs);
        ArticleProjectionRecord current = repository.ensureAndLockProjection(gen, event.articleId(), nowMs);

        long effectiveFloor = Math.max(current.contentRevision(), barrier.maxContentRevision());
        if (event.sourceRevision() <= effectiveFloor) {
            repository.updateInboxStatus(event.eventId(), "STALE_IGNORED", nowMs);
            return IngestionOutcome.STALE_IGNORED;
        }

        long nextProjectionRevision = current.projectionRevision() + 1L;
        ArticleProjectionRecord updated = new ArticleProjectionRecord(
                event.articleId(),
                true,
                event.sourceRevision(),
                event.visibility(),
                current.counterRevision(),
                current.viewCount(),
                nextProjectionRevision,
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
        repository.updateProjection(gen, updated);
        repository.advanceVersionBarrierIfHigher(event.articleId(), event.sourceRevision(), event.visibility(), nowMs);

        SearchIndexDocument tombstone = SearchIndexDocument.tombstone(
                updated.articleId(),
                updated.contentRevision()
        );
        repository.upsertPendingIndexTask(
                gen,
                generation.activePhysicalIndex(),
                updated.articleId(),
                nextProjectionRevision,
                SearchIndexOperation.TOMBSTONE,
                serializeDocument(tombstone),
                properties.getIndexer().getMaxAttempts(),
                nowMs
        );
        repository.updateInboxStatus(event.eventId(), "APPLIED", nowMs);
        return IngestionOutcome.APPLIED;
    }

    private IngestionOutcome applyCounterChanged(
            IndexGenerationState generation,
            ParsedContentEvent.CounterChangedEvent event,
            long nowMs) {
        long gen = generation.activeGeneration();
        ArticleProjectionRecord current = repository.ensureAndLockProjection(gen, event.articleId(), nowMs);
        if (event.counterRevision() <= current.counterRevision()) {
            repository.updateInboxStatus(event.eventId(), "STALE_IGNORED", nowMs);
            return IngestionOutcome.STALE_IGNORED;
        }

        boolean searchableNow = current.hasContent() && current.visibility() == ArticleVisibility.PUBLIC;
        long nextProjectionRevision = searchableNow
                ? current.projectionRevision() + 1L
                : current.projectionRevision();

        ArticleProjectionRecord updated = new ArticleProjectionRecord(
                current.articleId(),
                current.hasContent(),
                current.contentRevision(),
                current.visibility(),
                event.counterRevision(),
                event.viewCount(),
                nextProjectionRevision,
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
        repository.updateProjection(gen, updated);

        if (searchableNow) {
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
            repository.upsertPendingIndexTask(
                    gen,
                    generation.activePhysicalIndex(),
                    updated.articleId(),
                    nextProjectionRevision,
                    SearchIndexOperation.UPSERT,
                    serializeDocument(doc),
                    properties.getIndexer().getMaxAttempts(),
                    nowMs
            );
        }
        repository.updateInboxStatus(event.eventId(), "APPLIED", nowMs);
        return IngestionOutcome.APPLIED;
    }

    private String serializeDocument(SearchIndexDocument doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize SearchIndexDocument", ex);
        }
    }

    private static Long parsePositiveLongOrNull(String raw) {
        String normalized = CanonicalIdParser.normalizePositiveDecimalString(raw);
        return normalized == null ? null : Long.parseLong(normalized);
    }

    public enum IngestionOutcome {
        APPLIED,
        STALE_IGNORED,
        IGNORED_NON_SEARCH,
        DUPLICATE_SKIPPED,
        PROTOCOL_FAILED_RECORDED
    }
}
