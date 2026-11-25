package com.knode.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.community.client.CommunityUserClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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
class NotificationInboxHttpTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String RECEIVER_A = "9007199254740993";
    static final String RECEIVER_B = "9007199254740995";
    static final String BIG_AUTHOR = "9007199254740997";
    static final String COMMENT_ACTOR = "9007199254740999";
    static final String DELETED_ACTOR = "9007199254741001";

    static final long ART_PUBLIC_1 = 9_007_199_254_741_101L;
    static final long ART_PUBLIC_STREAM = 9_007_199_254_741_102L;
    static final long ART_WITHDRAWN = 9_007_199_254_741_103L;
    static final long ART_DELETED = 9_007_199_254_741_104L;
    static final long ART_BEFORE_FOLLOW = 9_007_199_254_741_105L;
    static final long ART_PROJECTED_WITHDRAWN = 9_007_199_254_741_106L;

    static final AtomicReference<String> USER_MODE = new AtomicReference<>("ok");
    static final AtomicReference<String> CONTENT_MODE = new AtomicReference<>("ok");
    static final AtomicInteger PROFILE_HITS = new AtomicInteger();
    static final AtomicInteger METADATA_HITS = new AtomicInteger();
    static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    static final HttpServer UPSTREAM = startUpstreamMock();

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired CommunityUserClient userClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        AuthenticationAndConfigTest.registerBaseProperties(p, "community_notif_test");
        String origin = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        p.add("KNODE_USER_ORIGIN", () -> origin);
        p.add("KNODE_USER_SERVICE_ID", () -> "knode-community");
        p.add("KNODE_USER_BOOTSTRAP_SECRET", () -> "community-bootstrap-secret");
        p.add("KNODE_CONTENT_ORIGIN", () -> origin);
    }

    @AfterAll
    static void tearDown() {
        UPSTREAM.stop(0);
        EXEC.shutdownNow();
    }

    @BeforeEach
    void reset() {
        USER_MODE.set("ok");
        CONTENT_MODE.set("ok");
        PROFILE_HITS.set(0);
        METADATA_HITS.set(0);
        userClient.clearTokenCache();
        AuthenticationAndConfigTest.NOW_MILLIS.set(1_795_521_600_000L);
        AuthenticationAndConfigTest.setActiveSession("blog", RECEIVER_A);
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/community-schema.sql")).execute(db.getDataSource());
    }

    @Test
    void inboxMergesStreamWithFollowBoundaryProtectsWithdrawnTitlesAndKeepsUnreadAccurate() throws Exception {
        String tokenA = blogToken(RECEIVER_A);

        // Empty inbox makes zero external HTTP calls
        var empty = send("GET", "/api/blog/notification/list", tokenA);
        assertEquals(200, empty.statusCode());
        assertEquals("0", JSON.readTree(empty.body()).path("data").path("total").textValue());
        assertEquals(0, PROFILE_HITS.get());
        assertEquals(0, METADATA_HITS.get());

        // RECEIVER_A followed BIG_AUTHOR at 10:00:00
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, TIMESTAMP '2026-11-24 10:00:00')",
                Long.parseLong(RECEIVER_A), Long.parseLong(BIG_AUTHOR));

        // 1. Publish event BEFORE follow time (09:00:00) -> MUST be excluded by e.create_time >= f.create_time
        db.update("INSERT INTO yan_author_publish_event(id, author_id, article_id, create_time) VALUES (11, ?, ?, TIMESTAMP '2026-11-24 09:00:00')",
                Long.parseLong(BIG_AUTHOR), ART_BEFORE_FOLLOW);
        // 2. Publish event AFTER follow time (11:00:00) for PUBLIC article -> included as id="-12", isRead=1
        db.update("INSERT INTO yan_author_publish_event(id, author_id, article_id, create_time) VALUES (12, ?, ?, TIMESTAMP '2026-11-24 11:00:00')",
                Long.parseLong(BIG_AUTHOR), ART_PUBLIC_STREAM);
        // 3. Publish event AFTER follow time (11:30:00) but local projection already marks WITHDRAWN -> excluded in SQL
        db.update("INSERT INTO community_article_projection(article_id, author_id, title, visibility, source_revision) VALUES (?, ?, NULL, 'WITHDRAWN', 3)",
                ART_PROJECTED_WITHDRAWN, Long.parseLong(BIG_AUTHOR));
        db.update("INSERT INTO yan_author_publish_event(id, author_id, article_id, create_time) VALUES (13, ?, ?, TIMESTAMP '2026-11-24 11:30:00')",
                Long.parseLong(BIG_AUTHOR), ART_PROJECTED_WITHDRAWN);

        // 4. Direct unread root COMMENT notification on PUBLIC article (10:30:00)
        db.update("""
                INSERT INTO yan_user_notification(id, receiver_id, actor_id, article_id, comment_id, root_comment_id, dedupe_key, type, content, is_read, create_time)
                VALUES (301, ?, ?, ?, 9007199254741201, -1, 'COMMENT:9007199254741201', 'COMMENT', 'Nice post!', 0, TIMESTAMP '2026-11-24 10:30:00')
                """, Long.parseLong(RECEIVER_A), Long.parseLong(COMMENT_ACTOR), ART_PUBLIC_1);

        // 5. Direct unread COMMENT_REPLY notification on WITHDRAWN article by DELETED_ACTOR (10:45:00)
        db.update("""
                INSERT INTO yan_user_notification(id, receiver_id, actor_id, article_id, comment_id, root_comment_id, dedupe_key, type, content, is_read, create_time)
                VALUES (302, ?, ?, ?, 9007199254741202, 9007199254741201, 'COMMENT_REPLY:9007199254741202', 'COMMENT_REPLY', 'Reply preview', 0, TIMESTAMP '2026-11-24 10:45:00')
                """, Long.parseLong(RECEIVER_A), Long.parseLong(DELETED_ACTOR), ART_WITHDRAWN);

        // 6. Direct read FOLLOWED notification (no articleId, 08:00:00)
        db.update("""
                INSERT INTO yan_user_notification(id, receiver_id, actor_id, article_id, comment_id, root_comment_id, dedupe_key, type, content, is_read, create_time)
                VALUES (300, ?, ?, NULL, NULL, NULL, 'FOLLOWED:1', 'FOLLOWED', NULL, 1, TIMESTAMP '2026-11-24 08:00:00')
                """, Long.parseLong(RECEIVER_A), Long.parseLong(COMMENT_ACTOR));

        // Unread count ONLY counts unread direct rows (301, 302) -> "2", never counts -12 stream row!
        var unread = send("GET", "/api/blog/notification/unread-count", tokenA);
        assertEquals(200, unread.statusCode());
        assertEquals("2", JSON.readTree(unread.body()).path("data").path("unreadCount").textValue());

        var inboxResp = send("GET", "/api/blog/notification/list?pageNum=1&pageSize=10", tokenA);
        assertEquals(200, inboxResp.statusCode(), inboxResp.body());
        assertFalse(inboxResp.body().contains("Secret Withdrawn Title"));
        assertFalse(inboxResp.body().contains(Long.toString(ART_BEFORE_FOLLOW)));
        assertFalse(inboxResp.body().contains(Long.toString(ART_PROJECTED_WITHDRAWN)));

        JsonNode data = JSON.readTree(inboxResp.body()).path("data");
        assertEquals("4", data.path("total").textValue());
        JsonNode rows = data.path("rows");
        assertEquals(4, rows.size());

        // Order: isRead ASC (302 @ 10:45, 301 @ 10:30), then isRead=1 (-12 @ 11:00, 300 @ 08:00)
        assertEquals("302", rows.get(0).path("id").textValue());
        assertEquals(0, rows.get(0).path("isRead").asInt());
        assertEquals(DELETED_ACTOR, rows.get(0).path("actorId").textValue());
        assertTrue(rows.get(0).path("actorNickName").isNull());
        assertEquals("MISSING", rows.get(0).path("actorInfoStatus").textValue());
        assertTrue(rows.get(0).path("articleTitle").isNull());
        assertEquals("UNAVAILABLE", rows.get(0).path("articleVisibilityStatus").textValue());
        assertEquals("9007199254741201", rows.get(0).path("rootCommentId").textValue());

        assertEquals("301", rows.get(1).path("id").textValue());
        assertEquals("Public Article One", rows.get(1).path("articleTitle").textValue());
        assertEquals("PUBLIC", rows.get(1).path("articleVisibilityStatus").textValue());
        assertEquals("-1", rows.get(1).path("rootCommentId").textValue());
        assertEquals("Commenter", rows.get(1).path("actorNickName").textValue());

        assertEquals("-12", rows.get(2).path("id").textValue());
        assertEquals(1, rows.get(2).path("isRead").asInt());
        assertEquals("ARTICLE_PUBLISHED", rows.get(2).path("type").textValue());
        assertEquals("Big Author Stream Post", rows.get(2).path("articleTitle").textValue());
        assertEquals("PUBLIC", rows.get(2).path("articleVisibilityStatus").textValue());

        assertEquals("300", rows.get(3).path("id").textValue());
        assertEquals("FOLLOWED", rows.get(3).path("type").textValue());
        assertTrue(rows.get(3).path("articleId").isNull());
        assertEquals("NOT_APPLICABLE", rows.get(3).path("articleVisibilityStatus").textValue());

        // Fail-closed check when Content metadata service is down (503) and User service is down (503)
        CONTENT_MODE.set("down");
        USER_MODE.set("down");
        var degraded = send("GET", "/notification/list?pageNum=1&pageSize=10", tokenA);
        assertEquals(200, degraded.statusCode());
        JsonNode degRows = JSON.readTree(degraded.body()).path("data").path("rows");
        assertEquals(4, degRows.size());
        assertTrue(degRows.get(1).path("articleTitle").isNull());
        assertEquals("TEMP_UNAVAILABLE", degRows.get(1).path("articleVisibilityStatus").textValue());
        assertTrue(degRows.get(1).path("actorNickName").isNull());
        assertEquals("UNAVAILABLE", degRows.get(1).path("actorInfoStatus").textValue());
    }

    @Test
    void enforcesOwnershipIsolationNegativeIdDismissalAndIdValidation() throws Exception {
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, TIMESTAMP '2026-11-24 10:00:00')",
                Long.parseLong(RECEIVER_A), Long.parseLong(BIG_AUTHOR));
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, TIMESTAMP '2026-11-24 10:00:00')",
                Long.parseLong(RECEIVER_B), Long.parseLong(BIG_AUTHOR));
        db.update("INSERT INTO yan_author_publish_event(id, author_id, article_id, create_time) VALUES (15, ?, ?, TIMESTAMP '2026-11-24 11:00:00')",
                Long.parseLong(BIG_AUTHOR), ART_PUBLIC_STREAM);

        db.update("""
                INSERT INTO yan_user_notification(id, receiver_id, actor_id, article_id, type, is_read, create_time)
                VALUES (501, ?, ?, ?, 'ARTICLE_PUBLISHED', 0, TIMESTAMP '2026-11-24 11:05:00')
                """, Long.parseLong(RECEIVER_A), Long.parseLong(COMMENT_ACTOR), ART_PUBLIC_1);
        db.update("""
                INSERT INTO yan_user_notification(id, receiver_id, actor_id, article_id, type, is_read, create_time)
                VALUES (502, ?, ?, ?, 'ARTICLE_PUBLISHED', 0, TIMESTAMP '2026-11-24 11:06:00')
                """, Long.parseLong(RECEIVER_B), Long.parseLong(COMMENT_ACTOR), ART_PUBLIC_1);

        // RECEIVER_B tries to markRead and delete RECEIVER_A's notification 501 -> 200 no-op, 501 remains unread & present!
        AuthenticationAndConfigTest.setActiveSession("blog", RECEIVER_B);
        String tokenB = blogToken(RECEIVER_B);
        assertEquals(200, send("PUT", "/api/blog/notification/501/read", tokenB).statusCode());
        assertEquals(200, send("DELETE", "/api/blog/notification/501", tokenB).statusCode());
        assertEquals(0, db.queryForObject("SELECT is_read FROM yan_user_notification WHERE id=501", Integer.class));

        // Switch back to RECEIVER_A: unread is still 1
        AuthenticationAndConfigTest.setActiveSession("blog", RECEIVER_A);
        String tokenA = blogToken(RECEIVER_A);
        assertEquals("1", JSON.readTree(send("GET", "/notification/unread-count", tokenA).body()).path("data").path("unreadCount").textValue());

        // Mark negative ID -15 read -> idempotent 200
        assertEquals(200, JSON.readTree(send("PUT", "/api/blog/notification/-15/read", tokenA).body()).path("code").asInt());
        assertEquals("1", JSON.readTree(send("GET", "/notification/unread-count", tokenA).body()).path("data").path("unreadCount").textValue());

        // MarkAllRead for RECEIVER_A -> RECEIVER_A unread becomes 0, while RECEIVER_B's 502 remains unread!
        assertEquals(200, send("PUT", "/api/blog/notification/read-all", tokenA).statusCode());
        assertEquals("0", JSON.readTree(send("GET", "/notification/unread-count", tokenA).body()).path("data").path("unreadCount").textValue());
        assertEquals(0, db.queryForObject("SELECT is_read FROM yan_user_notification WHERE id=502", Integer.class));

        // Delete negative ID -15 for RECEIVER_A -> permanently dismissed for RECEIVER_A, still visible for RECEIVER_B!
        assertEquals(200, send("DELETE", "/notification/-15", tokenA).statusCode());
        // Repeat delete is idempotent
        assertEquals(200, send("DELETE", "/api/blog/notification/-15", tokenA).statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM community_publish_event_dismissal WHERE receiver_id=?",
                Integer.class, Long.parseLong(RECEIVER_A)));
        var listAfterDismiss = JSON.readTree(send("GET", "/api/blog/notification/list", tokenA).body()).path("data");
        assertEquals("1", listAfterDismiss.path("total").textValue());
        assertEquals("501", listAfterDismiss.path("rows").get(0).path("id").textValue());

        // Bogus negative ID -999999 does NOT bloat dismissal table!
        assertEquals(200, send("DELETE", "/api/blog/notification/-999999", tokenA).statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM community_publish_event_dismissal", Integer.class));

        // RECEIVER_B still sees -15!
        AuthenticationAndConfigTest.setActiveSession("blog", RECEIVER_B);
        var listB = JSON.readTree(send("GET", "/api/blog/notification/list", blogToken(RECEIVER_B)).body()).path("data");
        assertEquals("2", listB.path("total").textValue());

        // Invalid IDs rejected with HTTP 200 code 515
        AuthenticationAndConfigTest.setActiveSession("blog", RECEIVER_A);
        for (String bad : List.of("0", "-0", "01", "-01", "1.5", "-9223372036854775808", "9223372036854775808", "abc")) {
            assertEquals(515, JSON.readTree(send("PUT", "/api/blog/notification/" + bad + "/read", tokenA).body()).path("code").asInt(), bad);
            assertEquals(515, JSON.readTree(send("DELETE", "/api/blog/notification/" + bad, tokenA).body()).path("code").asInt(), bad);
        }
    }

    @Test
    void internalNotificationsSummaryAndAdditiveUpgradeSqlWorkCorrectly() throws Exception {
        // Also verify additive 02a upgrade DDL cleanly applies on top of 01a tables
        db.execute("DROP TABLE yan_user_notification, yan_author_publish_event, community_publish_event_dismissal, community_article_projection");
        new ResourceDatabasePopulator(new ClassPathResource("db/community-notification-02a-upgrade.sql")).execute(db.getDataSource());

        String svcRead = AuthenticationAndConfigTest.serviceToken(
                "kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, "knode-content", "knode-community", List.of("community:notifications:read"));
        String tokenA = blogToken(RECEIVER_A);

        // Empty mailbox -> unreadCount="0", latestNotificationId=null
        var emptySummary = sendInternal("GET", "/internal/v1/community/notifications/summary", svcRead, tokenA);
        assertEquals(200, emptySummary.statusCode(), emptySummary.body());
        assertEquals("no-store", emptySummary.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode emptyNode = JSON.readTree(emptySummary.body());
        assertEquals(Set.of("unreadCount", "latestNotificationId", "asOf"), fieldNames(emptyNode));
        assertEquals("0", emptyNode.get("unreadCount").textValue());
        assertTrue(emptyNode.get("latestNotificationId").isNull());
        assertTrue(emptyNode.get("asOf").textValue().endsWith("Z"));

        // Add 1 unread direct notification (10:00) and 1 newer large-author publish event (11:00 -> id -25)
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, TIMESTAMP '2026-11-24 09:00:00')",
                Long.parseLong(RECEIVER_A), Long.parseLong(BIG_AUTHOR));
        db.update("INSERT INTO yan_user_notification(id, receiver_id, actor_id, article_id, type, is_read, create_time) VALUES (701, ?, ?, ?, 'COMMENT', 0, TIMESTAMP '2026-11-24 10:00:00')",
                Long.parseLong(RECEIVER_A), Long.parseLong(COMMENT_ACTOR), ART_PUBLIC_1);
        db.update("INSERT INTO yan_author_publish_event(id, author_id, article_id, create_time) VALUES (25, ?, ?, TIMESTAMP '2026-11-24 11:00:00')",
                Long.parseLong(BIG_AUTHOR), ART_PUBLIC_STREAM);

        var summary = sendInternal("GET", "/internal/v1/community/notifications/summary", svcRead, tokenA);
        assertEquals(200, summary.statusCode());
        JsonNode node = JSON.readTree(summary.body());
        assertEquals("1", node.get("unreadCount").textValue());
        assertEquals("-25", node.get("latestNotificationId").textValue());

        // Auth & parameter boundaries
        assertEquals(401, sendInternal("GET", "/internal/v1/community/notifications/summary", svcRead, null).statusCode());
        assertEquals(403, sendInternal("GET", "/internal/v1/community/notifications/summary",
                AuthenticationAndConfigTest.serviceToken("kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, "knode-content", "knode-community", List.of("community:relations:read")),
                tokenA).statusCode());
        assertEquals(400, sendInternal("GET", "/internal/v1/community/notifications/summary?receiverId=42", svcRead, tokenA).statusCode());

        AuthenticationAndConfigTest.setActiveSession("admin", RECEIVER_A);
        String adminToken = AuthenticationAndConfigTest.userToken("kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, RECEIVER_A, "admin", 600);
        assertEquals(403, sendInternal("GET", "/internal/v1/community/notifications/summary", svcRead, adminToken).statusCode());

        AuthenticationAndConfigTest.setActiveSession("blog", RECEIVER_A);
        db.execute("DROP TABLE yan_user_notification");
        assertEquals(503, sendInternal("GET", "/internal/v1/community/notifications/summary", svcRead, tokenA).statusCode());
    }

    private static Set<String> fieldNames(JsonNode obj) {
        java.util.HashSet<String> set = new java.util.HashSet<>();
        obj.fieldNames().forEachRemaining(set::add);
        return set;
    }

    private String blogToken(String userId) {
        return AuthenticationAndConfigTest.userToken("kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, userId, "blog", 600);
    }

    private HttpResponse<String> send(String method, String path, String userToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8));
        if (userToken != null) b.header("token", userToken);
        b.method(method, HttpRequest.BodyPublishers.noBody());
        return AuthenticationAndConfigTest.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendInternal(String method, String path, String bearer, String viewerToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (viewerToken != null) b.header("token", viewerToken);
        b.method(method, HttpRequest.BodyPublishers.noBody());
        return AuthenticationAndConfigTest.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startUpstreamMock() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXEC);
            server.createContext("/internal/v1/auth/service-tokens", exchange -> {
                try {
                    if ("down".equals(USER_MODE.get()) && "down".equals(CONTENT_MODE.get())) {
                        reply(exchange, 503, "{}");
                        return;
                    }
                    JsonNode req = JSON.readTree(exchange.getRequestBody());
                    String aud = req.path("audience").asText();
                    List<String> perms = new ArrayList<>();
                    req.path("permissions").forEach(n -> perms.add(n.asText()));
                    reply(exchange, 200, JSON.writeValueAsString(Map.of(
                            "accessToken", "svc-" + aud,
                            "tokenType", "Bearer",
                            "expiresIn", 120,
                            "audience", aud,
                            "permissions", perms)));
                } catch (Exception e) {
                    exchange.close();
                }
            });
            server.createContext("/internal/v1/users/public-profiles:batch", exchange -> {
                try {
                    PROFILE_HITS.incrementAndGet();
                    if ("down".equals(USER_MODE.get())) {
                        reply(exchange, 503, "{}");
                        return;
                    }
                    JsonNode req = JSON.readTree(exchange.getRequestBody());
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (JsonNode idNode : req.path("userIds")) {
                        String id = idNode.asText();
                        if (DELETED_ACTOR.equals(id)) {
                            items.add(Map.of("userId", id, "status", "MISSING"));
                        } else {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("userId", id);
                            m.put("status", "FOUND");
                            m.put("nickName", COMMENT_ACTOR.equals(id) ? "Commenter" : "BigCreator");
                            m.put("avatar", "https://example.invalid/" + id + ".png");
                            m.put("profileRevision", "2");
                            m.put("updatedAt", "2026-11-24T01:00:00Z");
                            items.add(m);
                        }
                    }
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("items", items)));
                } catch (Exception e) {
                    exchange.close();
                }
            });
            server.createContext("/internal/v1/content/articles/notification-metadata:batch", exchange -> {
                try {
                    METADATA_HITS.incrementAndGet();
                    if ("down".equals(CONTENT_MODE.get())) {
                        reply(exchange, 503, "{\"error\":{\"code\":\"DEPENDENCY_UNAVAILABLE\",\"message\":\"down\",\"retryable\":true}}");
                        return;
                    }
                    JsonNode req = JSON.readTree(exchange.getRequestBody());
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (JsonNode idNode : req.path("articleIds")) {
                        String id = idNode.asText();
                        long numeric = Long.parseLong(id);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("articleId", id);
                        if (numeric == ART_PUBLIC_1) {
                            m.put("sourceRevision", "1");
                            m.put("visibility", "PUBLIC");
                            m.put("decision", "READABLE");
                            m.put("title", "Public Article One");
                        } else if (numeric == ART_PUBLIC_STREAM) {
                            m.put("sourceRevision", "1");
                            m.put("visibility", "PUBLIC");
                            m.put("decision", "READABLE");
                            m.put("title", "Big Author Stream Post");
                        } else if (numeric == ART_WITHDRAWN) {
                            m.put("sourceRevision", "2");
                            m.put("visibility", "WITHDRAWN");
                            m.put("decision", "DENIED");
                        } else if (numeric == ART_DELETED) {
                            m.put("sourceRevision", "3");
                            m.put("visibility", "DELETED");
                            m.put("decision", "DENIED");
                        } else {
                            m.put("sourceRevision", null);
                            m.put("visibility", "MISSING");
                            m.put("decision", "DENIED");
                        }
                        items.add(m);
                    }
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("items", items)));
                } catch (Exception e) {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
