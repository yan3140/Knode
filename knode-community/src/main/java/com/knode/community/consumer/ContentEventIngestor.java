package com.knode.community.consumer;

import com.knode.observability.EventTraceContext;
import io.opentelemetry.api.trace.Span;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContentEventIngestor {
    public enum Outcome {
        APPLIED,
        DUPLICATE,
        IGNORED,
        UNSUPPORTED_DEAD,
        CONFLICT_DEAD
    }

    public record IngestResult(Outcome outcome, boolean ackStream) {}

    private record ExistingInbox(String payloadSha256, String status) {}
    private record ExistingProjection(
            String visibility,
            long sourceRevision,
            Long authorId,
            String publicationFactId,
            Instant publishedAt) {}

    private static final int DEFAULT_MAX_RETRY = 8;

    private final JdbcTemplate db;
    private final TransactionTemplate txTemplate;
    private final EventTraceContext eventTraces;

    public ContentEventIngestor(
            JdbcTemplate db,
            PlatformTransactionManager txManager,
            ObjectProvider<EventTraceContext> traceProvider) {
        this.db = db;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.txTemplate.setTimeout(5);
        this.eventTraces = traceProvider == null ? null : traceProvider.getIfAvailable();
    }

    public IngestResult ingest(String streamEntryId, String rawJson, int deliveryAttempt) {
        ContentEventParser.ParsedOutcome parsed = ContentEventParser.parse(streamEntryId, rawJson);
        int safeAttempt = Math.max(1, deliveryAttempt);
        String traceparent = extractTraceparent(parsed);

        Outcome outcome = txTemplate.execute(status -> applyInTransaction(streamEntryId, parsed));
        recordConsumerSpan(traceparent, outcome, safeAttempt);
        return new IngestResult(outcome, true);
    }

    private Outcome applyInTransaction(String streamEntryId, ContentEventParser.ParsedOutcome parsed) {
        if (parsed instanceof ContentEventParser.UnsupportedFact bad) {
            ExistingInbox existing = findInboxForUpdate(bad.syntheticEventId());
            if (existing != null) {
                return Outcome.DUPLICATE;
            }
            insertInbox(
                    bad.syntheticEventId(),
                    streamEntryId,
                    bad.eventType(),
                    bad.schemaVersion(),
                    bad.aggregateType(),
                    bad.aggregateId(),
                    null,
                    null,
                    null,
                    bad.payloadSha256(),
                    "DEAD",
                    bad.reason());
            return Outcome.UNSUPPORTED_DEAD;
        }

        if (parsed instanceof ContentEventParser.CounterFact counter) {
            Outcome dedup = checkExistingOrNull(counter.eventId(), counter.payloadSha256());
            if (dedup != null) {
                return dedup;
            }
            insertInbox(
                    counter.eventId(),
                    streamEntryId,
                    "ArticleCounterChanged",
                    counter.schemaVersion(),
                    "ARTICLE_COUNTER",
                    Long.toString(counter.articleId()),
                    counter.counterRevision(),
                    null,
                    counter.traceparent(),
                    counter.payloadSha256(),
                    "IGNORED",
                    null);
            return Outcome.IGNORED;
        }

        if (parsed instanceof ContentEventParser.ArticleFact article) {
            Outcome dedup = checkExistingOrNull(article.eventId(), article.payloadSha256());
            if (dedup != null) {
                return dedup;
            }
            insertInbox(
                    article.eventId(),
                    streamEntryId,
                    article.eventType(),
                    article.schemaVersion(),
                    "ARTICLE",
                    Long.toString(article.articleId()),
                    article.sourceRevision(),
                    article.publicationFactId(),
                    article.traceparent(),
                    article.payloadSha256(),
                    "SUCCEEDED",
                    null);
            String effectiveVisibility = upsertArticleProjection(article);
            if ("ArticlePublished".equals(article.eventType())
                    && article.publicationFactId() != null
                    && article.authorId() != null
                    && !"DELETED".equals(effectiveVisibility)) {
                enqueuePublishFanoutIfAbsent(article);
            }
            return Outcome.APPLIED;
        }

        if (parsed instanceof ContentEventParser.CommentFact comment) {
            Outcome dedup = checkExistingOrNull(comment.eventId(), comment.payloadSha256());
            if (dedup != null) {
                return dedup;
            }
            if (comment.actorId() == comment.receiverId()) {
                insertInbox(
                        comment.eventId(),
                        streamEntryId,
                        comment.eventType(),
                        comment.schemaVersion(),
                        "COMMENT",
                        Long.toString(comment.commentId()),
                        1L,
                        null,
                        comment.traceparent(),
                        comment.payloadSha256(),
                        "IGNORED",
                        null);
                return Outcome.IGNORED;
            }
            insertInbox(
                    comment.eventId(),
                    streamEntryId,
                    comment.eventType(),
                    comment.schemaVersion(),
                    "COMMENT",
                    Long.toString(comment.commentId()),
                    1L,
                    null,
                    comment.traceparent(),
                    comment.payloadSha256(),
                    "SUCCEEDED",
                    null);
            enqueueCommentDeliveryIfAbsent(comment);
            return Outcome.APPLIED;
        }

        throw new IllegalStateException("Unhandled parsed event type");
    }

    private Outcome checkExistingOrNull(String eventId, String payloadSha256) {
        ExistingInbox existing = findInboxForUpdate(eventId);
        if (existing == null) {
            return null;
        }
        if (existing.payloadSha256().equals(payloadSha256)) {
            return Outcome.DUPLICATE;
        }
        db.update(
                "UPDATE community_event_inbox SET last_error = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE event_id = ? AND last_error IS NULL",
                "Conflicting payload digest for existing eventId",
                eventId);
        return Outcome.CONFLICT_DEAD;
    }

    private ExistingInbox findInboxForUpdate(String eventId) {
        List<ExistingInbox> rows = db.query(
                "SELECT payload_sha256, status FROM community_event_inbox WHERE event_id = ? FOR UPDATE",
                (rs, n) -> new ExistingInbox(rs.getString(1), rs.getString(2)),
                eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertInbox(
            String eventId,
            String streamEntryId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            Long sourceRevision,
            String publicationFactId,
            String traceparent,
            String payloadSha256,
            String status,
            String lastError) {
        db.update("""
                INSERT INTO community_event_inbox(
                    event_id, stream_entry_id, event_type, schema_version, aggregate_type, aggregate_id,
                    source_revision, publication_fact_id, traceparent, payload_sha256, status, last_error,
                    received_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """,
                eventId,
                bounded(streamEntryId, 64),
                eventType,
                schemaVersion,
                aggregateType,
                aggregateId,
                sourceRevision,
                publicationFactId,
                traceparent,
                payloadSha256,
                status,
                bounded(lastError, 512));
    }

    private String upsertArticleProjection(ContentEventParser.ArticleFact fact) {
        List<ExistingProjection> rows = db.query(
                "SELECT visibility, source_revision, author_id, publication_fact_id, published_at "
                        + "FROM community_article_projection WHERE article_id = ? FOR UPDATE",
                (rs, n) -> {
                    long authorVal = rs.getLong(3);
                    Long author = rs.wasNull() ? null : authorVal;
                    Timestamp pubTs = rs.getTimestamp(5);
                    return new ExistingProjection(
                            rs.getString(1),
                            rs.getLong(2),
                            author,
                            rs.getString(4),
                            pubTs == null ? null : pubTs.toInstant());
                },
                fact.articleId());

        if (rows.isEmpty()) {
            String initialTitle = "PUBLIC".equals(fact.visibility()) ? fact.title() : null;
            db.update("""
                    INSERT INTO community_article_projection(
                        article_id, author_id, title, visibility, source_revision,
                        publication_fact_id, published_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                    """,
                    fact.articleId(),
                    fact.authorId(),
                    initialTitle,
                    fact.visibility(),
                    fact.sourceRevision(),
                    fact.publicationFactId(),
                    fact.publishedAt() == null ? null : Timestamp.from(fact.publishedAt()));
            return fact.visibility();
        }

        ExistingProjection current = rows.get(0);
        Long mergedAuthor = current.authorId() != null ? current.authorId() : fact.authorId();
        String mergedFactId = current.publicationFactId() != null ? current.publicationFactId() : fact.publicationFactId();
        Instant mergedPubAt = current.publishedAt() != null ? current.publishedAt() : fact.publishedAt();

        // Permanent DELETED tombstone barrier: never resurrect visibility or title from older or equal revisions.
        if ("DELETED".equals(current.visibility())) {
            long maxRev = Math.max(current.sourceRevision(), fact.sourceRevision());
            db.update("""
                    UPDATE community_article_projection
                    SET author_id = ?, title = NULL, source_revision = ?, publication_fact_id = ?, published_at = ?, updated_at = CURRENT_TIMESTAMP(3)
                    WHERE article_id = ?
                    """,
                    mergedAuthor,
                    maxRev,
                    mergedFactId,
                    mergedPubAt == null ? null : Timestamp.from(mergedPubAt),
                    fact.articleId());
            return "DELETED";
        }

        if (fact.sourceRevision() > current.sourceRevision()) {
            String nextTitle = "PUBLIC".equals(fact.visibility()) ? fact.title() : null;
            db.update("""
                    UPDATE community_article_projection
                    SET author_id = ?, title = ?, visibility = ?, source_revision = ?,
                        publication_fact_id = ?, published_at = ?, updated_at = CURRENT_TIMESTAMP(3)
                    WHERE article_id = ?
                    """,
                    mergedAuthor,
                    nextTitle,
                    fact.visibility(),
                    fact.sourceRevision(),
                    mergedFactId,
                    mergedPubAt == null ? null : Timestamp.from(mergedPubAt),
                    fact.articleId());
            return fact.visibility();
        }

        // Out-of-order older event (e.g. ArticlePublished rev=1 arriving after ArticleContentChanged rev=2):
        // Preserve newer visibility/title/revision while filling missing provenance metadata.
        db.update("""
                UPDATE community_article_projection
                SET author_id = ?, publication_fact_id = ?, published_at = ?, updated_at = CURRENT_TIMESTAMP(3)
                WHERE article_id = ?
                """,
                mergedAuthor,
                mergedFactId,
                mergedPubAt == null ? null : Timestamp.from(mergedPubAt),
                fact.articleId());
        return current.visibility();
    }

    private void enqueuePublishFanoutIfAbsent(ContentEventParser.ArticleFact fact) {
        String taskKey = "PUBLISH_FACT:" + fact.publicationFactId();
        Integer existing = db.queryForObject(
                "SELECT COUNT(*) FROM yan_notification_outbox WHERE task_key = ? OR article_id = ?",
                Integer.class,
                taskKey,
                fact.articleId());
        if (existing != null && existing > 0) {
            return;
        }
        try {
            db.update("""
                    INSERT INTO yan_notification_outbox(
                        event_id, article_id, author_id, publication_fact_id, task_key, cursor_follower_id,
                        status, retry_count, max_retry, next_retry_at, rerun_requested, traceparent,
                        process_count, create_time, update_time
                    ) VALUES (?, ?, ?, ?, ?, 0, 'PENDING', 0, ?, CURRENT_TIMESTAMP(3), 0, ?, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                    """,
                    fact.eventId(),
                    fact.articleId(),
                    fact.authorId(),
                    fact.publicationFactId(),
                    taskKey,
                    DEFAULT_MAX_RETRY,
                    fact.traceparent());
        } catch (DuplicateKeyException duplicate) {
            Integer recheck = db.queryForObject(
                    "SELECT COUNT(*) FROM yan_notification_outbox WHERE task_key = ? OR article_id = ?",
                    Integer.class,
                    taskKey,
                    fact.articleId());
            if (recheck == null || recheck < 1) {
                throw duplicate;
            }
        }
    }

    private void enqueueCommentDeliveryIfAbsent(ContentEventParser.CommentFact fact) {
        String notifType = "CommentCreated".equals(fact.eventType()) ? "COMMENT" : "COMMENT_REPLY";
        String taskKey = notifType + ":" + fact.commentId();
        Integer existing = db.queryForObject(
                "SELECT COUNT(*) FROM yan_notification_delivery_outbox WHERE task_key = ?",
                Integer.class,
                taskKey);
        if (existing != null && existing > 0) {
            return;
        }
        try {
            db.update("""
                    INSERT INTO yan_notification_delivery_outbox(
                        event_id, task_key, receiver_id, actor_id, article_id, comment_id, root_comment_id,
                        type, content, status, retry_count, max_retry, next_retry_at,
                        rerun_requested, traceparent, process_count, create_time, update_time
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, CURRENT_TIMESTAMP(3), 0, ?, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                    """,
                    fact.eventId(),
                    taskKey,
                    fact.receiverId(),
                    fact.actorId(),
                    fact.articleId(),
                    fact.commentId(),
                    fact.rootCommentId(),
                    notifType,
                    fact.contentPreview(),
                    DEFAULT_MAX_RETRY,
                    fact.traceparent());
        } catch (DuplicateKeyException duplicate) {
            Integer recheck = db.queryForObject(
                    "SELECT COUNT(*) FROM yan_notification_delivery_outbox WHERE task_key = ?",
                    Integer.class,
                    taskKey);
            if (recheck == null || recheck < 1) {
                throw duplicate;
            }
        }
    }

    private void recordConsumerSpan(String traceparent, Outcome outcome, int attempt) {
        if (eventTraces == null || outcome == null) {
            return;
        }
        EventTraceContext.Saved saved = traceparent == null
                ? null
                : eventTraces.fromStored(traceparent, null).orElse(null);
        Span span = eventTraces.startConsumer(saved, "community.content_event.ingest", attempt);
        try {
            span.setAttribute("knode.consumer.outcome", outcome.name());
        } finally {
            span.end();
        }
    }

    private static String extractTraceparent(ContentEventParser.ParsedOutcome parsed) {
        if (parsed instanceof ContentEventParser.ArticleFact a) return a.traceparent();
        if (parsed instanceof ContentEventParser.CommentFact c) return c.traceparent();
        if (parsed instanceof ContentEventParser.CounterFact cnt) return cnt.traceparent();
        return null;
    }

    private static String bounded(String val, int max) {
        if (val == null) return null;
        return val.length() <= max ? val : val.substring(0, max);
    }
}
