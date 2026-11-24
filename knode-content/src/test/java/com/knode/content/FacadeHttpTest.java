package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.comment.CommentCommandService;
import com.knode.content.comment.CommentInput;
import com.knode.content.comment.CommentRules;
import com.knode.content.facade.OriginPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class FacadeHttpTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String AUTHOR_ID = "9007199254740993";
    static final String VIEWER_ID = "9007199254740995";
    static final AtomicReference<String> USER_MODE = new AtomicReference<>("ok");
    static final AtomicReference<List<String>> ADMIN_PERMS = new AtomicReference<>(List.of("ops:outbox:read", "ops:outbox:retry"));
    static final AtomicReference<String> COMMUNITY_MODE = new AtomicReference<>("ok");
    static final AtomicReference<String> SEARCH_MODE = new AtomicReference<>("ok");
    static final AtomicReference<String> LAST_COMMUNITY_VIEWER_TOKEN = new AtomicReference<>();
    static final AtomicReference<String> LAST_SEARCH_OPERATOR = new AtomicReference<>();
    static final AtomicBoolean PEER_RECEIVED_BROWSER_ADMIN_TOKEN = new AtomicBoolean(false);
    static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
    static final HttpServer USER_SERVER = userProvider();
    static final HttpServer COMMUNITY_SERVER = communityProvider();
    static final HttpServer SEARCH_SERVER = searchProvider();

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService articles;
    @Autowired CommentCommandService comments;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_USER_ORIGIN", () -> "http://127.0.0.1:" + USER_SERVER.getAddress().getPort());
        p.add("KNODE_USER_SERVICE_ID", () -> "knode-content");
        p.add("KNODE_USER_BOOTSTRAP_SECRET", () -> "synthetic-secret");
        p.add("KNODE_COMMUNITY_ORIGIN", () -> "http://127.0.0.1:" + COMMUNITY_SERVER.getAddress().getPort());
        p.add("KNODE_SEARCH_ORIGIN", () -> "http://127.0.0.1:" + SEARCH_SERVER.getAddress().getPort());
        // Intentionally leave KNODE_KNOWLEDGE_ORIGIN unconfigured to verify UNCONFIGURED status!
    }

    @AfterAll static void stop() {
        USER_SERVER.stop(0);
        COMMUNITY_SERVER.stop(0);
        SEARCH_SERVER.stop(0);
        EXEC.shutdownNow();
    }

    @BeforeEach void setup() {
        USER_MODE.set("ok");
        ADMIN_PERMS.set(List.of("ops:outbox:read", "ops:outbox:retry"));
        COMMUNITY_MODE.set("ok");
        SEARCH_MODE.set("ok");
        LAST_COMMUNITY_VIEWER_TOKEN.set(null);
        LAST_SEARCH_OPERATOR.set(null);
        PEER_RECEIVED_BROWSER_ADMIN_TOKEN.set(false);
        setSession("blog", VIEWER_ID);
        for (String t : List.of("content_article_snapshot_item", "content_article_snapshot", "content_article_snapshot_gate",
                "content_article_change_fact", "content_article_change_batch", "content_article_change_head",
                "content_event_backfill", "content_event_attempt_audit", "content_event_replay_audit",
                "content_event_delivery", "content_article_counter_event", "content_comment_event",
                "content_comment", "content_comment_limit", "content_metadata_target", "content_metadata_job",
                "content_link", "content_write_fence", "content_article_event", "content_article_tag",
                "content_article", "content_tag", "content_category")) {
            db.execute("DROP TABLE IF EXISTS " + t);
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status,description) VALUES(2,'Java','0','cat')");
        db.update("INSERT INTO content_tag(id,name,del_flag,remark) VALUES(3,'Redis',0,'tag')");
    }

    private void setSession(String scope, String userId) {
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(
                new Session(1, AuthenticationHttpTest.SID, userId, scope, "ACTIVE", Instant.now().getEpochSecond() + 1800)));
    }

    private String userJwt(String scope, String userId) {
        Instant now = Instant.now();
        return Jwts.builder().header().keyId("test-key").and().issuer("knode-user").subject(userId)
                .audience().add("knode-api").and().claim("sid", AuthenticationHttpTest.SID)
                .claim("scope", scope).claim("token_use", "user").id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(10))).expiration(Date.from(now.plusSeconds(600)))
                .signWith(AuthenticationHttpTest.KEYS.getPrivate()).compact();
    }

    private HttpResponse<String> send(String method, String path, String token, String body, String extraHeader, String extraValue) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
        if (token != null) b.header("token", token);
        if (extraHeader != null) b.header(extraHeader, extraValue);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return AuthenticationHttpTest.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long createArticle(String author, String status, String title) {
        return articles.create(author, new ArticleCommand(title, null, "1", "body-" + title, List.of(3L), 2L, "summary", status), null);
    }

    @Test void profileAggregatesUserArticlesAndCommunityRelationsWithLongIds() throws Exception {
        long pub1 = createArticle(AUTHOR_ID, "0", "pub1");
        long draft = createArticle(AUTHOR_ID, "1", "draft");
        long withdrawn = createArticle(AUTHOR_ID, "0", "withdrawn");
        articles.setStatus(AUTHOR_ID, withdrawn, "1", null);
        long deleted = createArticle(AUTHOR_ID, "0", "deleted");
        articles.delete(AUTHOR_ID, deleted, null);
        createArticle(VIEWER_ID, "0", "other-author");

        // Anonymous request
        var anon = send("GET", "/api/blog/user/profile/" + AUTHOR_ID + "?pageNum=1&pageSize=10", null, null, null, null);
        assertEquals(200, anon.statusCode());
        JsonNode data = JSON.readTree(anon.body()).path("data");
        assertEquals(AUTHOR_ID, data.path("id").asText());
        assertEquals("Creator", data.path("nickName").asText());
        assertEquals("FOUND", data.path("userInfoStatus").asText());
        assertTrue(data.path("createTime").isNull());
        assertEquals("AVAILABLE", data.path("relationStatus").asText());
        assertEquals("12", data.path("followingCount").asText());
        assertEquals("9007199254740999", data.path("followerCount").asText());
        assertTrue(data.path("viewerFollowing").isNull());
        assertEquals("NOT_APPLICABLE", data.path("viewerFollowStatus").asText());
        assertEquals("1", data.path("articles").path("total").asText());
        assertEquals(Long.toString(pub1), data.path("articles").path("rows").get(0).path("id").asText());
        assertFalse(anon.body().contains("draft"));
        assertFalse(anon.body().contains("withdrawn"));
        assertFalse(anon.body().contains("deleted"));
        assertNull(LAST_COMMUNITY_VIEWER_TOKEN.get());

        // Authenticated non-self viewer receives viewerFollowing via verified token
        String viewerToken = userJwt("blog", VIEWER_ID);
        var authed = send("GET", "/user/profile/" + AUTHOR_ID, viewerToken, null, null, null);
        assertEquals(200, authed.statusCode());
        JsonNode authedData = JSON.readTree(authed.body()).path("data");
        assertEquals("AVAILABLE", authedData.path("viewerFollowStatus").asText());
        assertTrue(authedData.path("viewerFollowing").asBoolean());
        assertEquals(viewerToken, LAST_COMMUNITY_VIEWER_TOKEN.get());

        // Authenticated self viewer does NOT claim following self
        setSession("blog", AUTHOR_ID);
        LAST_COMMUNITY_VIEWER_TOKEN.set(null);
        var self = send("GET", "/api/blog/user/profile/" + AUTHOR_ID, userJwt("blog", AUTHOR_ID), null, null, null);
        assertEquals(200, self.statusCode());
        JsonNode selfData = JSON.readTree(self.body()).path("data");
        assertTrue(selfData.path("viewerFollowing").isNull());
        assertEquals("NOT_APPLICABLE", selfData.path("viewerFollowStatus").asText());
        assertNull(LAST_COMMUNITY_VIEWER_TOKEN.get());
    }

    @Test void profileDistinguishesMissingUserFromUnavailableUserAndDegradesCommunityHonestly() throws Exception {
        createArticle(AUTHOR_ID, "0", "pub1");

        // Primary user MISSING -> HTTP 200 code 515 (not 503)
        var missing = send("GET", "/api/blog/user/profile/404404", null, null, null, null);
        assertEquals(200, missing.statusCode());
        assertEquals(515, JSON.readTree(missing.body()).path("code").asInt());

        // Primary user UNAVAILABLE -> HTTP 503 code 500 (never fakes missing or empty!)
        USER_MODE.set("down");
        var userDown = send("GET", "/api/blog/user/profile/" + AUTHOR_ID, null, null, null, null);
        assertEquals(503, userDown.statusCode());
        assertEquals(500, JSON.readTree(userDown.body()).path("code").asInt());

        // Primary user OK, Community down / malformed -> profile succeeds with null counts & UNAVAILABLE status (NEVER 0 or false!)
        USER_MODE.set("ok");
        String viewerToken = userJwt("blog", VIEWER_ID);
        for (String mode : List.of("down", "bad-schema", "numeric-count", "extra-field", "wrong-user")) {
            COMMUNITY_MODE.set(mode);
            var degraded = send("GET", "/api/blog/user/profile/" + AUTHOR_ID, viewerToken, null, null, null);
            assertEquals(200, degraded.statusCode(), mode);
            JsonNode d = JSON.readTree(degraded.body()).path("data");
            assertEquals("FOUND", d.path("userInfoStatus").asText(), mode);
            assertEquals("UNAVAILABLE", d.path("relationStatus").asText(), mode);
            assertTrue(d.path("followingCount").isNull(), mode);
            assertTrue(d.path("followerCount").isNull(), mode);
            assertTrue(d.path("viewerFollowing").isNull(), mode);
            assertEquals("UNAVAILABLE", d.path("viewerFollowStatus").asText(), mode);
            assertEquals("1", d.path("articles").path("total").asText(), mode);
        }
    }

    @Test void profileEnforcesOptionalAuthAndInputValidation() throws Exception {
        for (String bad : List.of("0", "-1", "1.5", "9223372036854775808", "abc")) {
            var r = send("GET", "/api/blog/user/profile/" + bad, null, null, null, null);
            assertEquals(200, r.statusCode(), bad);
            assertEquals(515, JSON.readTree(r.body()).path("code").asInt(), bad);
        }
        assertEquals(515, JSON.readTree(send("GET", "/api/blog/user/profile/" + AUTHOR_ID + "?pageSize=101", null, null, null, null).body()).path("code").asInt());
        assertEquals(401, send("GET", "/api/blog/user/profile/" + AUTHOR_ID, null, null, "X-User-Id", AUTHOR_ID).statusCode());
        assertEquals(401, send("GET", "/api/blog/user/profile/" + AUTHOR_ID, "not-a-jwt", null, null, null).statusCode());
        assertEquals(401, send("POST", "/api/blog/user/profile/" + AUTHOR_ID, null, "{}", null, null).statusCode());

        // Revoked optional token -> 401
        AuthenticationHttpTest.STATE.set(null);
        assertEquals(401, send("GET", "/api/blog/user/profile/" + AUTHOR_ID, userJwt("blog", VIEWER_ID), null, null, null).statusCode());

        // Redis down on optional token -> 503
        AuthenticationHttpTest.STATE.set("UNAVAILABLE");
        assertEquals(503, send("GET", "/api/blog/user/profile/" + AUTHOR_ID, userJwt("blog", VIEWER_ID), null, null, null).statusCode());

        // Admin-scoped token on blog profile -> 403
        setSession("admin", VIEWER_ID);
        assertEquals(403, send("GET", "/api/blog/user/profile/" + AUTHOR_ID, userJwt("admin", VIEWER_ID), null, null, null).statusCode());
    }

    @Test void opsFacadeEnforcesAdminRbacAndPerDomainSummaryWithoutFakeZeros() throws Exception {
        createArticle(AUTHOR_ID, "0", "ops-article");
        setSession("admin", VIEWER_ID);
        String adminToken = userJwt("admin", VIEWER_ID);

        // Unauthenticated, blog token, and machine JWT all rejected
        assertEquals(401, send("GET", "/api/admin/ops/outbox/summary", null, null, null, null).statusCode());
        assertEquals(401, send("GET", "/api/admin/ops/outbox/summary", null, null, "Authorization", "Bearer fake-machine").statusCode());
        setSession("blog", VIEWER_ID);
        assertEquals(403, send("GET", "/api/admin/ops/outbox/summary", userJwt("blog", VIEWER_ID), null, null, null).statusCode());

        // Admin without ops permission -> 403
        setSession("admin", VIEWER_ID);
        ADMIN_PERMS.set(List.of("content:article:list"));
        assertEquals(403, send("GET", "/api/admin/ops/outbox/summary", adminToken, null, null, null).statusCode());

        // Admin with read permission gets honest per-domain summary (knowledge UNCONFIGURED, never fake 0!)
        ADMIN_PERMS.set(List.of("ops:outbox:read"));
        var ok = send("GET", "/api/admin/ops/outbox/summary", adminToken, null, null, null);
        assertEquals(200, ok.statusCode());
        JsonNode data = JSON.readTree(ok.body()).path("data");
        assertFalse(data.path("atomic").asBoolean());
        assertEquals("PARTIAL", data.path("overallStatus").asText());
        assertEquals("AVAILABLE", data.path("domains").path("contentProducer").path("status").asText());
        assertEquals("1", data.path("domains").path("contentProducer").path("states").path("PENDING").asText());
        assertEquals("AVAILABLE", data.path("domains").path("contentMetadata").path("status").asText());
        assertEquals("AVAILABLE", data.path("domains").path("search").path("status").asText());
        assertEquals("AVAILABLE", data.path("domains").path("notification").path("status").asText());
        assertEquals("UNCONFIGURED", data.path("domains").path("knowledge").path("status").asText());
        assertFalse(data.path("domains").path("knowledge").has("summary"));
        assertFalse(PEER_RECEIVED_BROWSER_ADMIN_TOKEN.get());

        // Search peer down -> DEGRADED, search UNAVAILABLE (no fabricated 0)
        SEARCH_MODE.set("down");
        var degraded = send("GET", "/ops/outbox/summary", adminToken, null, null, null);
        assertEquals(200, degraded.statusCode());
        JsonNode degData = JSON.readTree(degraded.body()).path("data");
        assertEquals("DEGRADED", degData.path("overallStatus").asText());
        assertEquals("UNAVAILABLE", degData.path("domains").path("search").path("status").asText());
        assertFalse(degData.path("domains").path("search").has("summary"));
    }

    @Test void opsFacadeRetrySeparatesProducerAndConsumerAndMapsInternalAuthErrorsTo503() throws Exception {
        long articleId = createArticle(AUTHOR_ID, "0", "retry-article");
        comments.create(VIEWER_ID, CommentRules.validate(new CommentInput("1", 1L, -1L, -1L, -1L, "friend note")), null);
        String articleEventId = db.queryForObject("SELECT event_id FROM content_article_event", String.class);
        String localEventId = db.queryForObject("SELECT event_id FROM content_comment_event WHERE event_type='LinkCommentCreated'", String.class);

        setSession("admin", VIEWER_ID);
        String adminToken = userJwt("admin", VIEWER_ID);

        // Read-only admin cannot retry
        ADMIN_PERMS.set(List.of("ops:outbox:read"));
        assertEquals(403, send("POST", "/api/admin/ops/outbox/search/retry", adminToken, "{}", null, null).statusCode());
        assertEquals(403, send("POST", "/api/admin/ops/outbox/content/" + articleEventId + "/redeliver", adminToken, "{\"replayRequestId\":\"adm-1\"}", null, null).statusCode());

        // Retry permission enabled
        ADMIN_PERMS.set(List.of("ops:outbox:read", "ops:outbox:retry"));

        // Spoofed operator/actor rejected
        assertEquals(400, send("POST", "/api/admin/ops/outbox/search/retry", adminToken, "{\"operator\":\"root\"}", null, null).statusCode());
        assertEquals(400, send("POST", "/api/admin/ops/outbox/content/" + articleEventId + "/redeliver", adminToken, "{\"replayRequestId\":\"adm-1\",\"actor\":\"spoof\"}", null, null).statusCode());

        // Single content event redeliver is idempotent and audits verified admin:<userId>
        var redeliver = send("POST", "/api/admin/ops/outbox/content/" + articleEventId + "/redeliver", adminToken, "{\"replayRequestId\":\"adm-req-1\"}", null, null);
        assertEquals(200, redeliver.statusCode());
        assertEquals("admin:" + VIEWER_ID, db.queryForObject("SELECT operator_service FROM content_event_replay_audit WHERE replay_request_id='adm-req-1'", String.class));
        assertEquals(200, send("POST", "/api/admin/ops/outbox/content/" + articleEventId + "/redeliver", adminToken, "{\"replayRequestId\":\"adm-req-1\"}", null, null).statusCode());
        // LOCAL_ONLY fact -> 409
        assertEquals(409, send("POST", "/api/admin/ops/outbox/content/" + localEventId + "/redeliver", adminToken, "{\"replayRequestId\":\"adm-local\"}", null, null).statusCode());

        // Search consumer retry passes server-derived admin:<userId>, never browser token
        var searchRetry = send("POST", "/api/admin/ops/outbox/search/retry", adminToken, "{\"replayRequestId\":\"srch-1\"}", null, null);
        assertEquals(200, searchRetry.statusCode());
        assertEquals("admin:" + VIEWER_ID, LAST_SEARCH_OPERATOR.get());
        assertFalse(PEER_RECEIVED_BROWSER_ADMIN_TOKEN.get());

        // Peer returning internal 401/403 maps to 503 (NEVER 401/403 so valid admin is not logged out!)
        SEARCH_MODE.set("auth401");
        assertEquals(503, send("POST", "/api/admin/ops/outbox/search/retry", adminToken, "{}", null, null).statusCode());
        SEARCH_MODE.set("auth403");
        assertEquals(503, send("GET", "/api/admin/ops/outbox/search", adminToken, null, null, null).statusCode());
        SEARCH_MODE.set("conflict");
        assertEquals(409, send("POST", "/api/admin/ops/outbox/search/retry", adminToken, "{}", null, null).statusCode());

        // Multi-domain /retry reports non-atomic partial outcome honestly
        SEARCH_MODE.set("ok");
        COMMUNITY_MODE.set("down");
        var multi = send("POST", "/api/admin/ops/outbox/retry", adminToken, "{\"replayRequestId\":\"multi-1\"}", null, null);
        assertEquals(200, multi.statusCode());
        JsonNode multiData = JSON.readTree(multi.body()).path("data");
        assertFalse(multiData.path("atomic").asBoolean());
        assertEquals("PARTIAL", multiData.path("overallStatus").asText());
        assertEquals("UNSUPPORTED", multiData.path("domains").path("contentProducer").path("status").asText());
        assertEquals("ACCEPTED", multiData.path("domains").path("search").path("status").asText());
        assertEquals("UNAVAILABLE", multiData.path("domains").path("notification").path("status").asText());
        assertEquals("UNCONFIGURED", multiData.path("domains").path("knowledge").path("status").asText());

        // When all configured peers fail -> HTTP 503 with full per-domain breakdown
        SEARCH_MODE.set("down");
        var allFailed = send("POST", "/api/admin/ops/outbox/retry", adminToken, "{\"replayRequestId\":\"multi-fail\"}", null, null);
        assertEquals(503, allFailed.statusCode());
        assertEquals("FAILED", JSON.readTree(allFailed.body()).path("data").path("overallStatus").asText());
    }

    @Test void opsFacadeBatchRetryDerivesCollisionFreeOrderIndependentKeys() throws Exception {
        createArticle(AUTHOR_ID, "0", "batch-article-a");
        createArticle(AUTHOR_ID, "0", "batch-article-b");
        List<String> eventIds = db.queryForList("SELECT event_id FROM content_article_event ORDER BY event_id ASC", String.class);
        assertEquals(2, eventIds.size());
        String eventA = eventIds.get(0);
        String eventB = eventIds.get(1);

        setSession("admin", VIEWER_ID);
        String adminToken = userJwt("admin", VIEWER_ID);
        ADMIN_PERMS.set(List.of("ops:outbox:read", "ops:outbox:retry"));

        String batchId1 = "a".repeat(60) + "0001";
        String batchId2 = "a".repeat(60) + "0002";
        assertEquals(64, batchId1.length());
        assertEquals(64, batchId2.length());

        String body1 = JSON.writeValueAsString(Map.of("replayRequestId", batchId1, "contentEventIds", List.of(eventA, eventB)));
        var res1 = send("POST", "/api/admin/ops/outbox/retry", adminToken, body1, null, null);
        assertEquals(200, res1.statusCode());
        JsonNode events1 = JSON.readTree(res1.body()).path("data").path("domains").path("contentProducer").path("events");
        assertEquals(2, events1.size());
        assertEquals("ACCEPTED", events1.get(0).path("status").asText());
        assertEquals("ACCEPTED", events1.get(1).path("status").asText());
        String key1A = events1.get(0).path("replayRequestId").asText();
        String key1B = events1.get(1).path("replayRequestId").asText();
        assertTrue(OriginPolicy.validReplayRequestId(key1A));
        assertTrue(OriginPolicy.validReplayRequestId(key1B));
        assertNotEquals(key1A, key1B);

        // Second 64-char batch ID differing ONLY in last char must NOT collide or false-dedupe with batchId1
        String body2 = JSON.writeValueAsString(Map.of("replayRequestId", batchId2, "contentEventIds", List.of(eventA, eventB)));
        var res2 = send("POST", "/api/admin/ops/outbox/retry", adminToken, body2, null, null);
        assertEquals(200, res2.statusCode());
        JsonNode events2 = JSON.readTree(res2.body()).path("data").path("domains").path("contentProducer").path("events");
        assertEquals("ACCEPTED", events2.get(0).path("status").asText());
        assertEquals("ACCEPTED", events2.get(1).path("status").asText());
        String key2A = events2.get(0).path("replayRequestId").asText();
        String key2B = events2.get(1).path("replayRequestId").asText();
        assertNotEquals(key1A, key2A);
        assertNotEquals(key1B, key2B);
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM content_event_replay_audit", Integer.class));

        // Retrying batchId1 with REORDERED event list [eventB, eventA] must idempotently produce identical per-event keys
        String body1Reordered = JSON.writeValueAsString(Map.of("replayRequestId", batchId1, "contentEventIds", List.of(eventB, eventA)));
        var resReordered = send("POST", "/api/admin/ops/outbox/retry", adminToken, body1Reordered, null, null);
        assertEquals(200, resReordered.statusCode());
        JsonNode eventsReordered = JSON.readTree(resReordered.body()).path("data").path("domains").path("contentProducer").path("events");
        assertEquals("ACCEPTED", eventsReordered.get(0).path("status").asText());
        assertEquals("ACCEPTED", eventsReordered.get(1).path("status").asText());
        assertEquals(eventB, eventsReordered.get(0).path("eventId").asText());
        assertEquals(key1B, eventsReordered.get(0).path("replayRequestId").asText());
        assertEquals(eventA, eventsReordered.get(1).path("eventId").asText());
        assertEquals(key1A, eventsReordered.get(1).path("replayRequestId").asText());
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM content_event_replay_audit", Integer.class));
    }

    @Test void opsFacadeRejectsEmptyAndAbnormalPeerRetryPayloads() throws Exception {
        setSession("admin", VIEWER_ID);
        String adminToken = userJwt("admin", VIEWER_ID);
        ADMIN_PERMS.set(List.of("ops:outbox:read", "ops:outbox:retry"));

        for (String badMode : List.of("empty-object", "unknown-fields", "failed-status", "negative-count", "error-code")) {
            SEARCH_MODE.set(badMode);
            var single = send("POST", "/api/admin/ops/outbox/search/retry", adminToken, "{\"replayRequestId\":\"bad-" + badMode + "\"}", null, null);
            assertEquals(503, single.statusCode(), badMode);

            var multi = send("POST", "/api/admin/ops/outbox/retry", adminToken, "{\"replayRequestId\":\"multi-" + badMode + "\"}", null, null);
            assertEquals(200, multi.statusCode(), badMode);
            JsonNode searchNode = JSON.readTree(multi.body()).path("data").path("domains").path("search");
            assertEquals("UNAVAILABLE", searchNode.path("status").asText(), badMode);
        }

        SEARCH_MODE.set("explicit-accepted");
        var ok = send("POST", "/api/admin/ops/outbox/search/retry", adminToken, "{\"replayRequestId\":\"ok-explicit\"}", null, null);
        assertEquals(200, ok.statusCode());
        assertEquals("ACCEPTED", JSON.readTree(ok.body()).path("data").path("status").asText());
    }

    @Test void originPolicyRejectsUnsafeOrigins() {
        for (String bad : List.of("http://localhost:8080", "http://example.org", "https://user:pass@example.org",
                "https://example.org/path", "https://example.org?a=1", "https://example.org#frag")) {
            assertThrows(IllegalArgumentException.class, () -> OriginPolicy.parseOptional(bad, "test"), bad);
        }
        assertNull(OriginPolicy.parseOptional("", "test"));
        assertNotNull(OriginPolicy.parseOptional("http://127.0.0.1:18090", "test"));
        assertNotNull(OriginPolicy.parseOptional("https://api.example.org", "test"));
    }

    private static HttpServer userProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXEC);
            server.createContext("/internal/v1/auth/service-tokens", exchange -> {
                try {
                    if ("down".equals(USER_MODE.get())) { reply(exchange, 503, "{}"); return; }
                    JsonNode req = JSON.readTree(exchange.getRequestBody());
                    String aud = req.path("audience").asText();
                    List<String> perms = new ArrayList<>();
                    req.path("permissions").forEach(p -> perms.add(p.asText()));
                    reply(exchange, 200, JSON.writeValueAsString(Map.of(
                            "accessToken", "svc-token-" + aud,
                            "tokenType", "Bearer",
                            "expiresIn", 120,
                            "audience", aud,
                            "permissions", perms)));
                } catch (Exception e) { exchange.close(); }
            });
            server.createContext("/internal/v1/users/public-profiles:batch", exchange -> {
                try {
                    if ("down".equals(USER_MODE.get())) { reply(exchange, 503, "{}"); return; }
                    JsonNode req = JSON.readTree(exchange.getRequestBody());
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (JsonNode id : req.path("userIds")) {
                        if (AUTHOR_ID.equals(id.asText())) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("userId", AUTHOR_ID);
                            m.put("status", "FOUND");
                            m.put("nickName", "Creator");
                            m.put("avatar", "https://example.invalid/a.png");
                            m.put("profileRevision", "3");
                            m.put("updatedAt", "2026-10-30T02:00:00Z");
                            items.add(m);
                        } else {
                            items.add(Map.of("userId", id.asText(), "status", "MISSING"));
                        }
                    }
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("items", items)));
                } catch (Exception e) { exchange.close(); }
            });
            server.createContext("/api/admin/auth/me", exchange -> {
                try {
                    if ("down".equals(USER_MODE.get())) { reply(exchange, 503, "{}"); return; }
                    Map<String, Object> data = Map.of(
                            "roles", List.of("admin"),
                            "permissions", ADMIN_PERMS.get(),
                            "user", Map.of("id", VIEWER_ID, "nickName", "Admin", "avatar", "", "sex", "0", "email", "a@example.invalid"));
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("code", 200, "msg", "ok", "data", data)));
                } catch (Exception e) { exchange.close(); }
            });
            server.start();
            return server;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static HttpServer communityProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXEC);
            server.createContext("/internal/v1/community/relations:batch", exchange -> {
                try {
                    String mode = COMMUNITY_MODE.get();
                    if ("down".equals(mode)) { reply(exchange, 503, "{}"); return; }
                    if ("bad-schema".equals(mode)) { reply(exchange, 200, "{\"unexpected\":true}"); return; }
                    String viewerToken = exchange.getRequestHeaders().getFirst("token");
                    LAST_COMMUNITY_VIEWER_TOKEN.set(viewerToken);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", "wrong-user".equals(mode) ? "999" : AUTHOR_ID);
                    item.put("followingCount", "numeric-count".equals(mode) ? 12 : "12");
                    item.put("followerCount", "9007199254740999");
                    item.put("viewerFollowing", viewerToken != null ? Boolean.TRUE : null);
                    if ("extra-field".equals(mode)) item.put("extra", "nope");
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("items", List.of(item))));
                } catch (Exception e) { exchange.close(); }
            });
            server.createContext("/internal/v1/ops/consumers/summary", exchange -> {
                try {
                    if (exchange.getRequestHeaders().containsKey("token")) PEER_RECEIVED_BROWSER_ADMIN_TOKEN.set(true);
                    if ("down".equals(COMMUNITY_MODE.get())) { reply(exchange, 503, "{}"); return; }
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("pending", "0", "failed", "1")));
                } catch (Exception e) { exchange.close(); }
            });
            server.createContext("/internal/v1/ops/consumers/retry", exchange -> {
                try {
                    if (exchange.getRequestHeaders().containsKey("token")) PEER_RECEIVED_BROWSER_ADMIN_TOKEN.set(true);
                    if ("down".equals(COMMUNITY_MODE.get())) { reply(exchange, 503, "{}"); return; }
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("requeued", "1")));
                } catch (Exception e) { exchange.close(); }
            });
            server.start();
            return server;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static HttpServer searchProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXEC);
            server.createContext("/internal/v1/ops/consumers/summary", exchange -> {
                try {
                    if (exchange.getRequestHeaders().containsKey("token")) PEER_RECEIVED_BROWSER_ADMIN_TOKEN.set(true);
                    if ("down".equals(SEARCH_MODE.get())) { reply(exchange, 503, "{}"); return; }
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("pending", "2", "failed", "0")));
                } catch (Exception e) { exchange.close(); }
            });
            server.createContext("/internal/v1/ops/consumers/events", exchange -> {
                try {
                    if (exchange.getRequestHeaders().containsKey("token")) PEER_RECEIVED_BROWSER_ADMIN_TOKEN.set(true);
                    String mode = SEARCH_MODE.get();
                    if ("auth403".equals(mode)) { reply(exchange, 403, "{}"); return; }
                    if ("down".equals(mode)) { reply(exchange, 503, "{}"); return; }
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("rows", List.of(), "total", "0")));
                } catch (Exception e) { exchange.close(); }
            });
            server.createContext("/internal/v1/ops/consumers/retry", exchange -> {
                try {
                    if (exchange.getRequestHeaders().containsKey("token")) PEER_RECEIVED_BROWSER_ADMIN_TOKEN.set(true);
                    String mode = SEARCH_MODE.get();
                    if ("auth401".equals(mode)) { reply(exchange, 401, "{}"); return; }
                    if ("conflict".equals(mode)) { reply(exchange, 409, "{}"); return; }
                    if ("down".equals(mode)) { reply(exchange, 503, "{}"); return; }
                    if ("empty-object".equals(mode)) { reply(exchange, 200, "{}"); return; }
                    if ("unknown-fields".equals(mode)) { reply(exchange, 200, "{\"ok\":true}"); return; }
                    if ("failed-status".equals(mode)) { reply(exchange, 200, "{\"status\":\"FAILED\",\"requeued\":\"0\"}"); return; }
                    if ("negative-count".equals(mode)) { reply(exchange, 200, "{\"requeued\":\"-1\"}"); return; }
                    if ("error-code".equals(mode)) { reply(exchange, 200, "{\"code\":500,\"status\":\"ACCEPTED\"}"); return; }
                    if ("explicit-accepted".equals(mode)) { reply(exchange, 200, "{\"status\":\"ACCEPTED\",\"replayRequestId\":\"ok-explicit\"}"); return; }
                    JsonNode body = JSON.readTree(exchange.getRequestBody());
                    LAST_SEARCH_OPERATOR.set(body.path("operator").asText());
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("requeued", "3")));
                } catch (Exception e) { exchange.close(); }
            });
            server.start();
            return server;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
