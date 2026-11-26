package com.knode.search.consumer.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.event.ContentEventParser;
import com.knode.search.consumer.event.ParsedContentEvent;
import com.knode.search.consumer.event.ProtocolFailure;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
public class ContentEventIngestionService {
    private final ContentEventParser eventParser;
    private final SearchConsumerRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final SearchConsumerProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ContentEventIngestionService(
            ContentEventParser eventParser,
            SearchConsumerRepository repository,
            @Qualifier("searchConsumerTransactionTemplate") TransactionTemplate transactionTemplate,
            SearchConsumerProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.eventParser = eventParser;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
        return transactionTemplate.execute(status -> {
            long nowMs = clock.millis();
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
                return applyPublicArticle(publicEvent, nowMs);
            }
            if (event instanceof ParsedContentEvent.HiddenArticleEvent hiddenEvent) {
                return applyHiddenArticle(hiddenEvent, nowMs);
            }
            if (event instanceof ParsedContentEvent.CounterChangedEvent counterEvent) {
                return applyCounterChanged(counterEvent, nowMs);
            }
            throw new IllegalStateException("Unhandled event subtype: " + event.getClass().getName());
        });
    }

    private IngestionOutcome applyPublicArticle(ParsedContentEvent.PublicArticleEvent event, long nowMs) {
        ArticleProjectionRecord current = repository.ensureAndLockProjection(event.articleId(), nowMs);
        if (event.sourceRevision() <= current.contentRevision()) {
            repository.updateInboxStatus(event.eventId(), "STALE_IGNORED", nowMs);
            return IngestionOutcome.STALE_IGNORED;
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
        repository.updateProjection(updated);

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

    private IngestionOutcome applyHiddenArticle(ParsedContentEvent.HiddenArticleEvent event, long nowMs) {
        ArticleProjectionRecord current = repository.ensureAndLockProjection(event.articleId(), nowMs);
        if (event.sourceRevision() <= current.contentRevision()) {
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
        repository.updateProjection(updated);

        SearchIndexDocument tombstone = SearchIndexDocument.tombstone(
                updated.articleId(),
                updated.contentRevision()
        );
        repository.upsertPendingIndexTask(
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

    private IngestionOutcome applyCounterChanged(ParsedContentEvent.CounterChangedEvent event, long nowMs) {
        ArticleProjectionRecord current = repository.ensureAndLockProjection(event.articleId(), nowMs);
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
        repository.updateProjection(updated);

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

    public enum IngestionOutcome {
        APPLIED,
        STALE_IGNORED,
        IGNORED_NON_SEARCH,
        DUPLICATE_SKIPPED,
        PROTOCOL_FAILED_RECORDED
    }
}
