package com.knode.community;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.community.auth.AuthConfiguration;
import com.knode.community.consumer.CommunityConsumerConfiguration;
import com.knode.community.consumer.ContentEventIngestor;
import com.knode.community.consumer.NotificationDeliveryWorker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "KNODE_MANAGEMENT_PORT=0",
            "KNODE_COMMUNITY_PORT=0",
            "KNODE_COMMUNITY_FANOUT_FOLLOWER_BATCH=2",
            "KNODE_COMMUNITY_LARGE_AUTHOR_THRESHOLD=6",
            "KNODE_COMMUNITY_RETRY_DELAY_SECONDS=1",
            "KNODE_COMMUNITY_MAX_RETRY_DELAY_SECONDS=4"
        })
@Import(AuthenticationAndConfigTest.TestFixtures.class)
class ConsumerAndFanoutTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ContentEventIngestor ingestor;
    @Autowired NotificationDeliveryWorker worker;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        AuthenticationAndConfigTest.registerBaseProperties(p, "community_consumer_test");
    }

    @BeforeEach
    void reset() {
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/community-schema.sql")).execute(db.getDataSource());
    }

    @Test
    void outOfOrderEditBeforeFirstPublishPreservesLatestTitleAndStillDeliversMultiBatchFanout() throws Exception {
        long authorId = 200L;
        // 5 followers + self-row check (< threshold 6 -> normal write-fanout in batches of 2)
        for (long f : List.of(10L, 20L, 30L, 40L, 50L)) {
            db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, CURRENT_TIMESTAMP(3))", f, authorId);
        }

        String pubFactId = UUID.randomUUID().toString();
        String editEventId = UUID.randomUUID().toString();
        String pubEventId = UUID.randomUUID().toString();

        // 1. ArticleContentChanged (rev=2, "Updated V2 Title") arrives FIRST!
        var editRes = ingestor.ingest("1-0", articleJson(editEventId, "ArticleContentChanged", 2, 1001L, 2L,
                "PUBLIC", pubFactId, authorId, "Updated V2 Title", "2026-11-24T10:00:00Z"), 1);
        assertEquals(ContentEventIngestor.Outcome.APPLIED, editRes.outcome());
        assertEquals(0, count("yan_notification_outbox"));
        assertEquals(2L, db.queryForObject("SELECT source_revision FROM community_article_projection WHERE article_id=1001", Long.class));
        assertEquals("Updated V2 Title", db.queryForObject("SELECT title FROM community_article_projection WHERE article_id=1001", String.class));

        // 2. ArticlePublished (rev=1, "Original V1 Title") arrives SECOND (out of order)!
        var pubRes = ingestor.ingest("2-0", articleJson(pubEventId, "ArticlePublished", 2, 1001L, 1L,
                "PUBLIC", pubFactId, authorId, "Original V1 Title", "2026-11-24T10:00:00Z"), 1);
        assertEquals(ContentEventIngestor.Outcome.APPLIED, pubRes.outcome());
        // Projection MUST keep rev=2 and "Updated V2 Title", while fanout task IS enqueued!
        assertEquals(2L, db.queryForObject("SELECT source_revision FROM community_article_projection WHERE article_id=1001", Long.class));
        assertEquals("Updated V2 Title", db.queryForObject("SELECT title FROM community_article_projection WHERE article_id=1001", String.class));
        assertEquals(1, count("yan_notification_outbox"));

        // Duplicate redelivery of same ArticlePublished eventId is idempotent
        assertEquals(ContentEventIngestor.Outcome.DUPLICATE,
                ingestor.ingest("3-0", articleJson(pubEventId, "ArticlePublished", 2, 1001L, 1L,
                        "PUBLIC", pubFactId, authorId, "Original V1 Title", "2026-11-24T10:00:00Z"), 2).outcome());
        assertEquals(1, count("yan_notification_outbox"));

        // 3. Execute fanout batches (batchSize=2 across 5 followers: 10,20 -> cursor 20; 30,40 -> cursor 40; 50 -> SUCCEEDED)
        assertEquals(1, worker.processFanoutBatch());
        assertEquals(20L, db.queryForObject("SELECT cursor_follower_id FROM yan_notification_outbox WHERE article_id=1001", Long.class));
        assertEquals("PENDING", db.queryForObject("SELECT status FROM yan_notification_outbox WHERE article_id=1001", String.class));
        assertEquals(2, count("yan_user_notification"));

        assertEquals(1, worker.processFanoutBatch());
        assertEquals(40L, db.queryForObject("SELECT cursor_follower_id FROM yan_notification_outbox WHERE article_id=1001", Long.class));
        assertEquals(4, count("yan_user_notification"));

        assertEquals(1, worker.processFanoutBatch());
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM yan_notification_outbox WHERE article_id=1001", String.class));
        assertEquals(5, count("yan_user_notification"));

        // Withdraw + Republish does NOT create a second fanout task
        ingestor.ingest("4-0", tombJson(UUID.randomUUID().toString(), "ArticleVisibilityChanged", 1001L, 3L, "WITHDRAWN", pubFactId), 1);
        assertEquals("WITHDRAWN", db.queryForObject("SELECT visibility FROM community_article_projection WHERE article_id=1001", String.class));
        ingestor.ingest("5-0", articleJson(UUID.randomUUID().toString(), "ArticleVisibilityChanged", 2, 1001L, 4L,
                "PUBLIC", pubFactId, authorId, "Republished Title", "2026-11-24T10:00:00Z"), 1);
        assertEquals(1, count("yan_notification_outbox"));
    }

    @Test
    void largeAuthorUsesReadFanoutDeleteBarrierHoldsAndCommentsCountersPoisonHandled() throws Exception {
        long bigAuthor = 300L;
        // 6 followers >= threshold 6 -> read-fanout into yan_author_publish_event!
        for (long f = 1; f <= 6; f++) {
            db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, CURRENT_TIMESTAMP(3))", f, bigAuthor);
        }
        String pf = UUID.randomUUID().toString();
        ingestor.ingest("10-0", articleJson(UUID.randomUUID().toString(), "ArticlePublished", 2, 2002L, 1L,
                "PUBLIC", pf, bigAuthor, "Big Post", "2026-11-24T11:00:00Z"), 1);
        assertEquals(1, worker.processFanoutBatch());
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM yan_notification_outbox WHERE article_id=2002", String.class));
        assertEquals(1, count("yan_author_publish_event"));
        assertEquals(0, count("yan_user_notification"));

        // Permanent DELETED barrier: delete rev=5, then late edit rev=4 must NOT resurrect PUBLIC or title
        ingestor.ingest("11-0", tombJson(UUID.randomUUID().toString(), "ArticleDeleted", 2002L, 5L, "DELETED", pf), 1);
        assertEquals("DELETED", db.queryForObject("SELECT visibility FROM community_article_projection WHERE article_id=2002", String.class));
        ingestor.ingest("12-0", articleJson(UUID.randomUUID().toString(), "ArticleContentChanged", 2, 2002L, 4L,
                "PUBLIC", pf, bigAuthor, "Ghost Resurrected", "2026-11-24T11:00:00Z"), 1);
        assertEquals("DELETED", db.queryForObject("SELECT visibility FROM community_article_projection WHERE article_id=2002", String.class));
        assertNull(db.queryForObject("SELECT title FROM community_article_projection WHERE article_id=2002", String.class));

        // CommentCreated (actor != receiver -> delivered) & self-comment (actor == receiver -> IGNORED)
        assertEquals(ContentEventIngestor.Outcome.APPLIED,
                ingestor.ingest("13-0", commentJson(UUID.randomUUID().toString(), "CommentCreated", 501L, 2002L, -1L, -1L, 77L, bigAuthor, "Hello author"), 1).outcome());
        assertEquals(ContentEventIngestor.Outcome.IGNORED,
                ingestor.ingest("14-0", commentJson(UUID.randomUUID().toString(), "CommentCreated", 502L, 2002L, -1L, -1L, bigAuthor, bigAuthor, "Self note"), 1).outcome());
        assertEquals(1, count("yan_notification_delivery_outbox"));
        assertEquals(1, worker.processDirectBatch());
        assertEquals(1, count("yan_user_notification"));
        assertEquals("Hello author", db.queryForObject("SELECT content FROM yan_user_notification WHERE comment_id=501", String.class));

        // Counter event -> IGNORED; malformed JSON -> UNSUPPORTED_DEAD in community_event_inbox
        assertEquals(ContentEventIngestor.Outcome.IGNORED,
                ingestor.ingest("15-0", counterJson(UUID.randomUUID().toString(), 2002L, 3L, 120L), 1).outcome());
        assertEquals(ContentEventIngestor.Outcome.UNSUPPORTED_DEAD,
                ingestor.ingest("16-0", "{not-valid-json", 1).outcome());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM community_event_inbox WHERE status='DEAD'", Integer.class));
    }

    @Test
    void staleLeaseCannotOverwriteReclaimedTaskAndExhaustedRetriesEnterDeadLetter() {
        db.update("""
                INSERT INTO yan_notification_delivery_outbox(
                    task_key, receiver_id, actor_id, type, status, retry_count, max_retry, next_retry_at, rerun_requested, process_count
                ) VALUES ('FOLLOWED:1:2', 2, 1, 'FOLLOWED', 'PENDING', 0, 2, CURRENT_TIMESTAMP(3), 0, 0)
                """);

        // Worker 1 claims task
        List<NotificationDeliveryWorker.ClaimedDirectTask> claim1 = worker.claimDirectTasks();
        assertEquals(1, claim1.size());
        // Simulate Worker 1 stalling past locked_until
        db.update("UPDATE yan_notification_delivery_outbox SET locked_until = TIMESTAMPADD(SECOND, -10, CURRENT_TIMESTAMP(3)) WHERE id=?",
                claim1.get(0).id());

        // Worker 2 reclaims the expired task with a fresh lease_token
        List<NotificationDeliveryWorker.ClaimedDirectTask> claim2 = worker.claimDirectTasks();
        assertEquals(1, claim2.size());
        assertNotEquals(claim1.get(0).leaseToken(), claim2.get(0).leaseToken());

        // Late Worker 1 wakes up and tries to execute -> rejected by lease_token CAS!
        assertFalse(worker.executeClaimedDirect(claim1.get(0)));
        assertEquals("PROCESSING", db.queryForObject("SELECT status FROM yan_notification_delivery_outbox WHERE id=?", String.class, claim2.get(0).id()));
        assertEquals(claim2.get(0).leaseToken(), db.queryForObject("SELECT lease_token FROM yan_notification_delivery_outbox WHERE id=?", String.class, claim2.get(0).id()));

        // Worker 2 finishes cleanly -> SUCCEEDED
        assertTrue(worker.executeClaimedDirect(claim2.get(0)));
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM yan_notification_delivery_outbox WHERE id=?", String.class, claim2.get(0).id()));
        assertEquals(1, count("yan_user_notification"));

        // Exhausted retries -> DEAD when yan_user_notification is temporarily unavailable (max_retry=1)
        db.update("""
                INSERT INTO yan_notification_delivery_outbox(
                    id, task_key, receiver_id, actor_id, type, status, retry_count, max_retry, next_retry_at, rerun_requested, process_count
                ) VALUES (99, 'FOLLOWED:8:9', 9, 8, 'FOLLOWED', 'PENDING', 0, 1, CURRENT_TIMESTAMP(3), 0, 0)
                """);
        db.execute("ALTER TABLE yan_user_notification RENAME TO yan_user_notification_hidden");
        try {
            assertEquals(0, worker.processDirectBatch());
            assertEquals("DEAD", db.queryForObject("SELECT status FROM yan_notification_delivery_outbox WHERE id=99", String.class));
            assertNotNull(db.queryForObject("SELECT dead_letter_at FROM yan_notification_delivery_outbox WHERE id=99", Object.class));
        } finally {
            db.execute("ALTER TABLE yan_user_notification_hidden RENAME TO yan_user_notification");
        }

        // Stream config isolation check: reusing auth Redis credentials or hostname fails fast
        CommunityConsumerConfiguration cfg = new CommunityConsumerConfiguration();
        assertThrows(IllegalArgumentException.class, () -> cfg.communityContentStream(
                new AuthConfiguration.Settings("test"), ingestor, true,
                "127.0.0.1", "6379", "auth_ro", "different_pw", true, 20, 30000L, "auth_ro", "secret_ro"));
        assertThrows(IllegalArgumentException.class, () -> cfg.communityContentStream(
                new AuthConfiguration.Settings("test"), ingestor, true,
                "redis.example.org", "6379", "stream_u", "stream_pw", true, 20, 30000L, "auth_ro", "secret_ro"));
    }

    private int count(String table) {
        Integer c = db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return c == null ? 0 : c;
    }

    private static String articleJson(
            String eventId, String eventType, int version, long articleId, long rev,
            String visibility, String pubFactId, long authorId, String title, String pubAt) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("articleId", Long.toString(articleId));
        payload.put("authorId", Long.toString(authorId));
        payload.put("title", title);
        payload.put("summary", "summary");
        payload.put("content", "# body");
        payload.put("thumbnail", null);
        payload.put("isTop", "0");
        payload.put("isComment", "1");
        payload.put("category", Map.of("id", "2", "name", "Java"));
        payload.put("tags", List.of(Map.of("id", "3", "name", "Spring")));
        payload.put("createTime", "2026-11-24T09:00:00Z");
        payload.put("publishedAt", pubAt);
        payload.put("viewCount", "0");
        payload.put("counterRevision", "0");

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId);
        env.put("eventType", eventType);
        env.put("schemaVersion", version);
        env.put("producer", "knode-content");
        env.put("aggregateType", "ARTICLE");
        env.put("aggregateId", Long.toString(articleId));
        env.put("sourceRevision", Long.toString(rev));
        env.put("publicationFactId", pubFactId);
        env.put("occurredAt", "2026-11-24T10:00:00Z");
        env.put("visibility", visibility);
        env.put("traceContext", Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        env.put("payload", payload);
        return JSON.writeValueAsString(env);
    }

    private static String tombJson(
            String eventId, String eventType, long articleId, long rev, String visibility, String pubFactId) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId);
        env.put("eventType", eventType);
        env.put("schemaVersion", 2);
        env.put("producer", "knode-content");
        env.put("aggregateType", "ARTICLE");
        env.put("aggregateId", Long.toString(articleId));
        env.put("sourceRevision", Long.toString(rev));
        env.put("publicationFactId", pubFactId);
        env.put("occurredAt", "2026-11-24T11:30:00Z");
        env.put("visibility", visibility);
        env.put("payload", Map.of("articleId", Long.toString(articleId)));
        return JSON.writeValueAsString(env);
    }

    private static String commentJson(
            String eventId, String eventType, long commentId, long articleId,
            long rootId, long toId, long actorId, long receiverId, String preview) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commentId", Long.toString(commentId));
        payload.put("articleId", Long.toString(articleId));
        payload.put("rootCommentId", Long.toString(rootId));
        payload.put("toCommentId", Long.toString(toId));
        payload.put("actorId", Long.toString(actorId));
        payload.put("receiverId", Long.toString(receiverId));
        payload.put("commentType", "0");
        payload.put("contentPreview", preview);

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId);
        env.put("eventType", eventType);
        env.put("schemaVersion", 1);
        env.put("producer", "knode-content");
        env.put("aggregateType", "COMMENT");
        env.put("aggregateId", Long.toString(commentId));
        env.put("sourceRevision", "1");
        env.put("occurredAt", "2026-11-24T12:00:00Z");
        env.put("payload", payload);
        return JSON.writeValueAsString(env);
    }

    private static String counterJson(String eventId, long articleId, long counterRev, long viewCount) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId);
        env.put("eventType", "ArticleCounterChanged");
        env.put("schemaVersion", 1);
        env.put("producer", "knode-content");
        env.put("aggregateType", "ARTICLE_COUNTER");
        env.put("aggregateId", Long.toString(articleId));
        env.put("sourceRevision", Long.toString(counterRev));
        env.put("occurredAt", "2026-11-24T12:05:00Z");
        env.put("visibility", "PUBLIC");
        env.put("payload", Map.of(
                "articleId", Long.toString(articleId),
                "viewCount", Long.toString(viewCount),
                "counterRevision", Long.toString(counterRev)));
        return JSON.writeValueAsString(env);
    }
}
