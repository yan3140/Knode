package com.knode.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.community.consumer.ContentEventIngestor;
import com.knode.community.consumer.NotificationDeliveryWorker;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_COMMUNITY_PORT=0"})
@Import(AuthenticationAndConfigTest.TestFixtures.class)
class CommunityOpsHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ContentEventIngestor ingestor;
    @Autowired NotificationDeliveryWorker worker;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        AuthenticationAndConfigTest.registerBaseProperties(p, "community_ops_test");
    }

    @BeforeEach
    void reset() {
        AuthenticationAndConfigTest.NOW_MILLIS.set(1_795_521_600_000L);
        AuthenticationAndConfigTest.setActiveSession("blog", "1001");
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/community-schema.sql")).execute(db.getDataSource());
    }

    @Test
    void enforcesPureMachineAuthAndRejectsSpoofedHeadersViewerTokensOrInvalidArguments() throws Exception {
        String readToken = serviceToken("knode-content", List.of("ops:read"));
        String retryToken = serviceToken("knode-content", List.of("ops:retry"));
        String blogToken = AuthenticationAndConfigTest.userToken(
                "kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, "1001", "blog", 600);

        // Missing token -> 401; wrong audience -> 401
        assertEquals(401, sendOps("GET", "/internal/v1/ops/consumers/summary", null, null).statusCode());
        String wrongAud = AuthenticationAndConfigTest.serviceToken(
                "kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, "knode-content", "knode-search", List.of("ops:read"));
        assertEquals(401, sendOps("GET", "/internal/v1/ops/consumers/summary", wrongAud, null).statusCode());

        // Viewer token header MUST be rejected on pure machine ops endpoints
        HttpRequest withViewerHeader = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/ops/consumers/summary"))
                .header("Authorization", "Bearer " + readToken)
                .header("token", blogToken)
                .GET().build();
        assertEquals(401, AuthenticationAndConfigTest.HTTP.send(withViewerHeader, HttpResponse.BodyHandlers.ofString()).statusCode());

        // Forbidden spoofed X-Operator header -> 401
        HttpRequest spoofedOp = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/ops/consumers/retry"))
                .header("Authorization", "Bearer " + retryToken)
                .header("X-Operator", "admin:1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"replayRequestId\":\"req-1\"}"))
                .build();
        assertEquals(401, AuthenticationAndConfigTest.HTTP.send(spoofedOp, HttpResponse.BodyHandlers.ofString()).statusCode());

        // Insufficient permission: ops:read calling /retry -> 403; ops:retry calling /summary -> 403
        assertEquals(403, sendOps("POST", "/internal/v1/ops/consumers/retry", readToken, "{\"replayRequestId\":\"req-1\"}").statusCode());
        assertEquals(403, sendOps("GET", "/internal/v1/ops/consumers/summary", retryToken, null).statusCode());

        // Invalid query params or body fields -> 400
        assertEquals(400, sendOps("GET", "/internal/v1/ops/consumers/events?pageNum=0&pageSize=20", readToken, null).statusCode());
        assertEquals(400, sendOps("GET", "/internal/v1/ops/consumers/events?pageNum=1&pageSize=101", readToken, null).statusCode());
        assertEquals(400, sendOps("GET", "/internal/v1/ops/consumers/events?status=UNKNOWN", readToken, null).statusCode());
        assertEquals(400, sendOps("GET", "/internal/v1/ops/consumers/events?extra=1", readToken, null).statusCode());
        assertEquals(400, sendOps("POST", "/internal/v1/ops/consumers/retry", retryToken,
                "{\"replayRequestId\":\"req-1\",\"actor\":\"spoof\"}").statusCode());
        assertEquals(400, sendOps("POST", "/internal/v1/ops/consumers/not-a-uuid/retry", retryToken,
                "{\"replayRequestId\":\"req-bad-uuid\",\"operator\":\"admin:1\"}").statusCode());
    }

    @Test
    void summaryEventsAndReplayStateMachinePreserveActiveLeasesAndAuditIdempotency() throws Exception {
        String readToken = serviceToken("knode-content", List.of("ops:read"));
        String retryToken = serviceToken("knode-content", List.of("ops:retry"));

        String deadEventId = UUID.randomUUID().toString();
        String activeEventId = UUID.randomUUID().toString();
        String expiredEventId = UUID.randomUUID().toString();

        // 1. Seed a DEAD direct delivery task, an active PROCESSING fanout task, an expired PROCESSING direct task, and 1 poison event
        db.update("""
                INSERT INTO yan_notification_delivery_outbox(
                    event_id, task_key, receiver_id, actor_id, comment_id, article_id, root_comment_id,
                    type, content, status, retry_count, max_retry, next_retry_at, dead_letter_at, last_error
                ) VALUES (?, 'COMMENT:701', 200, 101, 701, 5001, -1, 'COMMENT', 'Need retry', 'DEAD', 8, 8,
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 'DB_TEMP_DOWN')
                """, deadEventId);
        db.update("""
                INSERT INTO yan_notification_outbox(
                    event_id, article_id, author_id, publication_fact_id, task_key, cursor_follower_id,
                    status, retry_count, max_retry, next_retry_at, locked_until, lease_token
                ) VALUES (?, 6001, 300, 'pf-active', 'PUBLISH_FACT:pf-active', 0,
                          'PROCESSING', 0, 8, CURRENT_TIMESTAMP(3), TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(3)), 'active-lease-1')
                """, activeEventId);
        db.update("""
                INSERT INTO yan_notification_delivery_outbox(
                    event_id, task_key, receiver_id, actor_id, type, status, retry_count, max_retry,
                    next_retry_at, locked_until, lease_token
                ) VALUES (?, 'FOLLOWED:5:6', 6, 5, 'FOLLOWED', 'PROCESSING', 1, 8,
                          CURRENT_TIMESTAMP(3), TIMESTAMPADD(SECOND, -30, CURRENT_TIMESTAMP(3)), 'expired-lease-1')
                """, expiredEventId);
        ingestor.ingest("99-0", "{malformed-poison-json", 1);
        String poisonEventId = db.queryForObject("SELECT event_id FROM community_event_inbox WHERE status='DEAD'", String.class);
        assertNotNull(poisonEventId);

        // 2. Verify /summary counts (all decimal strings)
        var sumResp = sendOps("GET", "/internal/v1/ops/consumers/summary", readToken, null);
        assertEquals(200, sumResp.statusCode());
        JsonNode sum = JSON.readTree(sumResp.body());
        assertEquals("AVAILABLE", sum.path("status").asText());
        assertEquals("1", sum.path("inbox").path("DEAD").asText());
        assertEquals("1", sum.path("fanoutOutbox").path("PROCESSING").asText());
        assertEquals("0", sum.path("fanoutOutbox").path("expiredProcessing").asText());
        assertEquals("1", sum.path("directOutbox").path("DEAD").asText());
        assertEquals("1", sum.path("directOutbox").path("PROCESSING").asText());
        assertEquals("1", sum.path("directOutbox").path("expiredProcessing").asText());

        // 3. Verify /events filtering & string total
        var deadList = sendOps("GET", "/internal/v1/ops/consumers/events?pageNum=1&pageSize=20&status=DEAD", readToken, null);
        assertEquals(200, deadList.statusCode());
        JsonNode deadJson = JSON.readTree(deadList.body());
        assertTrue(deadJson.path("total").isTextual());
        assertEquals("2", deadJson.path("total").asText());
        assertEquals(2, deadJson.path("rows").size());

        // 4. Single retry edge cases: 404 unknown, 409 active lease, 409 poison event
        assertEquals(404, sendOps("POST", "/internal/v1/ops/consumers/" + UUID.randomUUID() + "/retry", retryToken,
                "{\"replayRequestId\":\"req-missing\",\"operator\":\"admin:1\"}").statusCode());
        assertEquals(409, sendOps("POST", "/internal/v1/ops/consumers/" + activeEventId + "/retry", retryToken,
                "{\"replayRequestId\":\"req-active\",\"operator\":\"admin:1\"}").statusCode());
        assertEquals("active-lease-1", db.queryForObject("SELECT lease_token FROM yan_notification_outbox WHERE event_id=?", String.class, activeEventId));
        assertEquals(409, sendOps("POST", "/internal/v1/ops/consumers/" + poisonEventId + "/retry", retryToken,
                "{\"replayRequestId\":\"req-poison\",\"operator\":\"admin:1\"}").statusCode());

        // 5. Single retry on DEAD task -> 200 ACCEPTED ("requeued": "1"), then worker delivers notification!
        var singleOk = sendOps("POST", "/internal/v1/ops/consumers/" + deadEventId + "/retry", retryToken,
                "{\"replayRequestId\":\"req-single-1\",\"operator\":\"admin:9\"}").statusCode();
        assertEquals(200, singleOk);
        assertEquals("PENDING", db.queryForObject("SELECT status FROM yan_notification_delivery_outbox WHERE event_id=?", String.class, deadEventId));
        assertEquals(0, db.queryForObject("SELECT retry_count FROM yan_notification_delivery_outbox WHERE event_id=?", Integer.class, deadEventId));

        // Repeat exact same replayRequestId + target + operator -> idempotent 200
        var dupOk = sendOps("POST", "/internal/v1/ops/consumers/" + deadEventId + "/retry", retryToken,
                "{\"replayRequestId\":\"req-single-1\",\"operator\":\"admin:9\"}");
        assertEquals(200, dupOk.statusCode());
        assertEquals("ACCEPTED", JSON.readTree(dupOk.body()).path("status").asText());
        assertEquals("1", JSON.readTree(dupOk.body()).path("requeued").asText());

        // Same replayRequestId with different operator or bulk target -> 409 IDEMPOTENCY_CONFLICT
        assertEquals(409, sendOps("POST", "/internal/v1/ops/consumers/" + deadEventId + "/retry", retryToken,
                "{\"replayRequestId\":\"req-single-1\",\"operator\":\"admin:999\"}").statusCode());
        assertEquals(409, sendOps("POST", "/internal/v1/ops/consumers/retry", retryToken,
                "{\"replayRequestId\":\"req-single-1\",\"operator\":\"admin:9\"}").statusCode());

        // 6. Bulk retry recovers expired PROCESSING lease + FAILED fanout task while preserving active PROCESSING lease!
        String failedFanoutId = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO yan_notification_outbox(
                    event_id, article_id, author_id, publication_fact_id, task_key, cursor_follower_id,
                    status, retry_count, max_retry, next_retry_at, last_error
                ) VALUES (?, 6002, 301, 'pf-failed', 'PUBLISH_FACT:pf-failed', 0,
                          'FAILED', 3, 8, TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(3)), 'TEMP_ERR')
                """, failedFanoutId);

        var bulkResp = sendOps("POST", "/internal/v1/ops/consumers/retry", retryToken,
                "{\"replayRequestId\":\"req-bulk-1\",\"operator\":\"admin:9\"}");
        assertEquals(200, bulkResp.statusCode());
        assertEquals("ACCEPTED", JSON.readTree(bulkResp.body()).path("status").asText());
        assertEquals("2", JSON.readTree(bulkResp.body()).path("requeued").asText());
        assertEquals("PENDING", db.queryForObject("SELECT status FROM yan_notification_delivery_outbox WHERE event_id=?", String.class, expiredEventId));
        assertEquals("PENDING", db.queryForObject("SELECT status FROM yan_notification_outbox WHERE event_id=?", String.class, failedFanoutId));
        assertEquals("PROCESSING", db.queryForObject("SELECT status FROM yan_notification_outbox WHERE event_id=?", String.class, activeEventId));
        assertEquals("active-lease-1", db.queryForObject("SELECT lease_token FROM yan_notification_outbox WHERE event_id=?", String.class, activeEventId));

        // Deliver both direct tasks (deadEventId + expiredEventId) -> SUCCEEDED, then verify NOOP on SUCCEEDED single retry
        assertEquals(2, worker.processDirectBatch());
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM yan_notification_delivery_outbox WHERE event_id=?", String.class, deadEventId));
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM yan_notification_delivery_outbox WHERE event_id=?", String.class, expiredEventId));

        var noopResp = sendOps("POST", "/internal/v1/ops/consumers/" + deadEventId + "/retry", retryToken,
                "{\"replayRequestId\":\"req-noop-1\",\"operator\":\"admin:9\"}");
        assertEquals(200, noopResp.statusCode());
        assertEquals("NOOP", JSON.readTree(noopResp.body()).path("status").asText());
        assertEquals("0", JSON.readTree(noopResp.body()).path("requeued").asText());

        // 7. DB failure on /summary -> 503 DEPENDENCY_UNAVAILABLE (never fake 0!)
        db.execute("ALTER TABLE community_event_inbox RENAME TO community_event_inbox_down");
        try {
            var downResp = sendOps("GET", "/internal/v1/ops/consumers/summary", readToken, null);
            assertEquals(503, downResp.statusCode());
            assertEquals("DEPENDENCY_UNAVAILABLE", JSON.readTree(downResp.body()).path("error").path("code").asText());
        } finally {
            db.execute("ALTER TABLE community_event_inbox_down RENAME TO community_event_inbox");
        }
    }

    private String serviceToken(String sub, List<String> perms) {
        return AuthenticationAndConfigTest.serviceToken(
                "kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, sub, "knode-community", perms);
    }

    private HttpResponse<String> sendOps(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return AuthenticationAndConfigTest.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
