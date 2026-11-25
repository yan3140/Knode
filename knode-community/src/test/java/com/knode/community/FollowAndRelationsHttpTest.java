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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class FollowAndRelationsHttpTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String FOLLOWER_A = "9007199254740993";
    static final String FOLLOWEE_B = "9007199254740995";
    static final String BANNED_USER = "9007199254740997";
    static final String DELETED_USER = "9007199254740999";
    static final String MISSING_USER = "9223372036854775807";

    static final AtomicReference<String> USER_SERVER_MODE = new AtomicReference<>("ok");
    static final AtomicInteger TOKEN_ISSUE_CALLS = new AtomicInteger();
    static final AtomicInteger VALIDITY_CALLS = new AtomicInteger();
    static final AtomicInteger PROFILE_CALLS = new AtomicInteger();
    static final List<Integer> VALIDITY_BATCH_SIZES = new CopyOnWriteArrayList<>();
    static final AtomicInteger OVERLAP_ACTIVE = new AtomicInteger();
    static final AtomicInteger OVERLAP_PEAK = new AtomicInteger();
    static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    static final HttpServer USER_SERVER = startUserServer();

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired CommunityUserClient userClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        AuthenticationAndConfigTest.registerBaseProperties(p, "community_follow_test");
        p.add("KNODE_COMMUNITY_RELATION_SCAN_LIMIT", () -> 3200);
        p.add("KNODE_USER_ORIGIN", () -> "http://127.0.0.1:" + USER_SERVER.getAddress().getPort());
        p.add("KNODE_USER_SERVICE_ID", () -> "knode-community");
        p.add("KNODE_USER_BOOTSTRAP_SECRET", () -> "community-bootstrap-secret");
    }

    @AfterAll
    static void stopServer() {
        USER_SERVER.stop(0);
        EXEC.shutdownNow();
    }

    @BeforeEach
    void reset() {
        USER_SERVER_MODE.set("ok");
        TOKEN_ISSUE_CALLS.set(0);
        VALIDITY_CALLS.set(0);
        PROFILE_CALLS.set(0);
        VALIDITY_BATCH_SIZES.clear();
        OVERLAP_PEAK.set(0);
        userClient.clearTokenCache();
        AuthenticationAndConfigTest.NOW_MILLIS.set(1_795_521_600_000L);
        AuthenticationAndConfigTest.setActiveSession("blog", FOLLOWER_A);
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/community-schema.sql")).execute(db.getDataSource());
    }

    @Test
    void followValidatesTargetRejectsSelfAndFailsClosedWhenUserDown() throws Exception {
        String tokenA = blogToken(FOLLOWER_A);

        // Self-follow rejected before any network or DB call
        var self = send("PUT", "/api/blog/follow/" + FOLLOWER_A, tokenA);
        assertEquals(200, self.statusCode());
        assertEquals(515, JSON.readTree(self.body()).path("code").asInt());
        assertEquals(0, VALIDITY_CALLS.get());
        assertEquals(0, count("yan_user_follow"));

        // Invalid path IDs -> HTTP 200 code 515
        for (String bad : List.of("0", "-5", "01", "1.5", "9223372036854775808", "abc")) {
            var res = send("PUT", "/follow/" + bad, tokenA);
            assertEquals(200, res.statusCode(), bad);
            assertEquals(515, JSON.readTree(res.body()).path("code").asInt(), bad);
        }
        assertEquals(0, VALIDITY_CALLS.get());

        // Missing user (exists=false) and banned user (exists=true, active=false) -> code 515
        assertEquals(515, JSON.readTree(send("PUT", "/api/blog/follow/" + DELETED_USER, tokenA).body()).path("code").asInt());
        assertEquals(515, JSON.readTree(send("PUT", "/api/blog/follow/" + BANNED_USER, tokenA).body()).path("code").asInt());
        assertEquals(0, count("yan_user_follow"));
        assertEquals(0, count("yan_notification_delivery_outbox"));

        // User service unavailable / bad schema -> HTTP 503 code 500, zero writes
        for (String failMode : List.of("down", "malformed-validity")) {
            USER_SERVER_MODE.set(failMode);
            var down = send("PUT", "/api/blog/follow/" + FOLLOWEE_B, tokenA);
            assertEquals(503, down.statusCode(), failMode);
            assertEquals(500, JSON.readTree(down.body()).path("code").asInt(), failMode);
            assertEquals(0, count("yan_user_follow"), failMode);
        }
    }

    @Test
    void concurrentFollowIsIdempotentAndOutboxFailureRollsBackRelationAtomically() throws Exception {
        String tokenA = blogToken(FOLLOWER_A);
        int threads = 12;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                var resp = send("PUT", "/api/blog/follow/" + FOLLOWEE_B, tokenA);
                assertEquals(200, resp.statusCode());
                return JSON.readTree(resp.body()).path("code").asInt();
            });
        }
        List<Future<Integer>> results = EXEC.invokeAll(tasks);
        for (Future<Integer> f : results) {
            assertEquals(200, f.get(10, TimeUnit.SECONDS));
        }

        assertEquals(1, count("yan_user_follow"));
        assertEquals(1, count("yan_notification_delivery_outbox"));
        String expectedTaskKey = "FOLLOWED:" + FOLLOWER_A + ":" + FOLLOWEE_B;
        assertEquals("PENDING", db.queryForObject(
                "SELECT status FROM yan_notification_delivery_outbox WHERE task_key=?", String.class, expectedTaskKey));

        // Simulate task already delivered (SUCCEEDED), then unfollow while User service is DOWN, then refollow
        db.update("UPDATE yan_notification_delivery_outbox SET status='SUCCEEDED' WHERE task_key=?", expectedTaskKey);
        USER_SERVER_MODE.set("down");
        int validityBeforeUnfollow = VALIDITY_CALLS.get();
        var unfollow = send("DELETE", "/follow/" + FOLLOWEE_B, tokenA);
        assertEquals(200, unfollow.statusCode());
        assertEquals(200, JSON.readTree(unfollow.body()).path("code").asInt());
        assertEquals(validityBeforeUnfollow, VALIDITY_CALLS.get());
        assertEquals(0, count("yan_user_follow"));
        // Repeat unfollow is idempotent
        assertEquals(200, JSON.readTree(send("DELETE", "/api/blog/follow/" + FOLLOWEE_B, tokenA).body()).path("code").asInt());

        // Refollow when User recovers: relation recreated, SUCCEEDED task NOT reset to PENDING (anti-spam)
        USER_SERVER_MODE.set("ok");
        assertEquals(200, JSON.readTree(send("PUT", "/api/blog/follow/" + FOLLOWEE_B, tokenA).body()).path("code").asInt());
        assertEquals(1, count("yan_user_follow"));
        assertEquals(1, count("yan_notification_delivery_outbox"));
        assertEquals("SUCCEEDED", db.queryForObject(
                "SELECT status FROM yan_notification_delivery_outbox WHERE task_key=?", String.class, expectedTaskKey));

        // Transactional rollback test: drop outbox table and follow a new valid user 42 -> yan_user_follow must roll back!
        db.execute("DROP TABLE yan_notification_delivery_outbox");
        var failedTx = send("PUT", "/api/blog/follow/42", tokenA);
        assertEquals(503, failedTx.statusCode());
        assertEquals(500, JSON.readTree(failedTx.body()).path("code").asInt());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM yan_user_follow WHERE followee_id=42", Integer.class));
    }

    @Test
    void followListsPreserveLongPrecisionHideDeletedAccountsAndCacheServiceTokens() throws Exception {
        String tokenA = blogToken(FOLLOWER_A);

        // Empty list makes zero public-profiles:batch calls
        var emptyFollowing = send("GET", "/api/blog/follow/following", tokenA);
        assertEquals(200, emptyFollowing.statusCode());
        assertEquals("0", JSON.readTree(emptyFollowing.body()).path("data").path("total").textValue());
        assertEquals(0, PROFILE_CALLS.get());

        // Insert two following relations for FOLLOWER_A: one active/FOUND (FOLLOWEE_B), one deleted/MISSING (DELETED_USER)
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, TIMESTAMP '2026-11-24 02:00:00')",
                Long.parseLong(FOLLOWER_A), Long.parseLong(DELETED_USER));
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, TIMESTAMP '2026-11-24 03:00:00')",
                Long.parseLong(FOLLOWER_A), Long.parseLong(FOLLOWEE_B));

        var following = send("GET", "/api/blog/follow/following?pageNum=1&pageSize=8", tokenA);
        assertEquals(200, following.statusCode());
        JsonNode data = JSON.readTree(following.body()).path("data");
        assertEquals("1", data.path("total").textValue());
        JsonNode rows = data.path("rows");
        assertEquals(1, rows.size());
        assertEquals(FOLLOWEE_B, rows.get(0).path("userId").textValue());
        assertEquals("Author B", rows.get(0).path("nickName").textValue());
        assertEquals("FOUND", rows.get(0).path("userInfoStatus").textValue());
        assertTrue(rows.get(0).path("followTime").textValue().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));


        // Service token reuse check: second list call reuses cached service token without re-calling /auth/service-tokens
        int issuedBefore = TOKEN_ISSUE_CALLS.get();
        assertEquals(200, send("GET", "/follow/following?pageNum=1&pageSize=8", tokenA).statusCode());
        assertEquals(issuedBefore, TOKEN_ISSUE_CALLS.get());

        // Both the validity and profile grants refresh after their pre-expiry window.
        AuthenticationAndConfigTest.NOW_MILLIS.addAndGet(110_000L);
        AuthenticationAndConfigTest.setActiveSession("blog", FOLLOWER_A);
        tokenA = blogToken(FOLLOWER_A);
        assertEquals(200, send("GET", "/follow/following?pageNum=1&pageSize=8", tokenA).statusCode());
        assertEquals(issuedBefore + 2, TOKEN_ISSUE_CALLS.get());

        // Followers endpoint for FOLLOWEE_B
        AuthenticationAndConfigTest.setActiveSession("blog", FOLLOWEE_B);
        var followers = send("GET", "/api/blog/follow/followers", blogToken(FOLLOWEE_B));
        assertEquals(200, followers.statusCode());
        JsonNode followerRows = JSON.readTree(followers.body()).path("data").path("rows");
        assertEquals(1, followerRows.size());
        assertEquals(FOLLOWER_A, followerRows.get(0).path("userId").textValue());

        // Public /{userId}/following anonymous & optional token checks
        var anonPublic = send("GET", "/api/blog/follow/" + FOLLOWER_A + "/following?pageNum=1&pageSize=8", null);
        assertEquals(200, anonPublic.statusCode());
        assertEquals("1", JSON.readTree(anonPublic.body()).path("data").path("total").textValue());

        // Deleted target user on /{userId}/following -> code 515; existing banned user -> 200
        assertEquals(515, JSON.readTree(send("GET", "/api/blog/follow/" + DELETED_USER + "/following", null).body()).path("code").asInt());
        assertEquals(200, JSON.readTree(send("GET", "/api/blog/follow/" + BANNED_USER + "/following", null).body()).path("code").asInt());

        // Invalid pagination / unexpected query param -> code 515
        assertEquals(515, JSON.readTree(send("GET", "/api/blog/follow/" + FOLLOWER_A + "/following?pageSize=101", null).body()).path("code").asInt());
        assertEquals(515, JSON.readTree(send("GET", "/api/blog/follow/" + FOLLOWER_A + "/following?unexpected=1", null).body()).path("code").asInt());

        // Optional token on /{userId}/following: invalid -> 401, Redis down -> 503
        assertEquals(401, send("GET", "/api/blog/follow/" + FOLLOWER_A + "/following", "bad-jwt").statusCode());
        AuthenticationAndConfigTest.SESSION_STATE.set("UNAVAILABLE");
        assertEquals(503, send("GET", "/api/blog/follow/" + FOLLOWER_A + "/following", blogToken(FOLLOWEE_B)).statusCode());

        // User service down on non-empty following list -> HTTP 503 code 500 (never fake empty rows)
        AuthenticationAndConfigTest.setActiveSession("blog", FOLLOWER_A);
        USER_SERVER_MODE.set("down");
        var userDownList = send("GET", "/api/blog/follow/following", blogToken(FOLLOWER_A));
        assertEquals(503, userDownList.statusCode());
        assertEquals(500, JSON.readTree(userDownList.body()).path("code").asInt());
    }

    @Test
    void internalRelationsBatchMatchesContentClientExactSchemaAndEnforcesAuth() throws Exception {
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, CURRENT_TIMESTAMP(3))",
                Long.parseLong(FOLLOWER_A), Long.parseLong(FOLLOWEE_B));
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id, create_time) VALUES (?, ?, CURRENT_TIMESTAMP(3))",
                42L, Long.parseLong(FOLLOWEE_B));

        String svcToken = AuthenticationAndConfigTest.serviceToken(
                "kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, "knode-content", "knode-community", List.of("community:relations:read"));
        String body = JSON.writeValueAsString(Map.of(
                "userIds", List.of(FOLLOWEE_B, FOLLOWER_A, FOLLOWEE_B, "999")));

        // 1. Anonymous batch (no viewer token): viewerFollowing must be explicit JSON null on every item
        var anonResp = sendInternal("POST", "/internal/v1/community/relations:batch", svcToken, null, body);
        assertEquals(200, anonResp.statusCode(), anonResp.body());
        assertEquals("no-store", anonResp.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode anonRoot = JSON.readTree(anonResp.body());
        assertEquals(1, anonRoot.size());
        JsonNode anonItems = anonRoot.get("items");
        assertEquals(3, anonItems.size());
        assertExactRelationItem(anonItems.get(0), FOLLOWEE_B, "0", "2", null);
        assertExactRelationItem(anonItems.get(1), FOLLOWER_A, "1", "0", null);
        assertExactRelationItem(anonItems.get(2), "999", "0", "0", null);

        // 2. Authenticated viewer FOLLOWER_A: FOLLOWEE_B -> true, self (FOLLOWER_A) -> null (NEVER false!), 999 -> false
        AuthenticationAndConfigTest.setActiveSession("blog", FOLLOWER_A);
        var viewerResp = sendInternal("POST", "/internal/v1/community/relations:batch", svcToken, blogToken(FOLLOWER_A), body);
        assertEquals(200, viewerResp.statusCode(), viewerResp.body());
        JsonNode viewerItems = JSON.readTree(viewerResp.body()).get("items");
        assertExactRelationItem(viewerItems.get(0), FOLLOWEE_B, "0", "2", Boolean.TRUE);
        assertExactRelationItem(viewerItems.get(1), FOLLOWER_A, "1", "0", null);
        assertExactRelationItem(viewerItems.get(2), "999", "0", "0", Boolean.FALSE);

        // 3. Machine & viewer auth error codes
        assertInternalError(401, "INVALID_SERVICE_TOKEN",
                sendInternal("POST", "/internal/v1/community/relations:batch", null, null, body));
        assertInternalError(401, "INVALID_SERVICE_TOKEN",
                sendInternal("POST", "/internal/v1/community/relations:batch",
                        AuthenticationAndConfigTest.serviceToken("kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, "knode-content", "knode-user", List.of("community:relations:read")),
                        null, body));
        assertInternalError(403, "SERVICE_PERMISSION_DENIED",
                sendInternal("POST", "/internal/v1/community/relations:batch",
                        AuthenticationAndConfigTest.serviceToken("kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, "knode-content", "knode-community", List.of("ops:read")),
                        null, body));
        assertInternalError(401, "SESSION_REVOKED",
                sendInternal("POST", "/internal/v1/community/relations:batch", svcToken, "bad-viewer-jwt", body));

        AuthenticationAndConfigTest.setActiveSession("admin", FOLLOWER_A);
        String adminJwt = AuthenticationAndConfigTest.userToken("kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, FOLLOWER_A, "admin", 600);
        assertInternalError(403, "USER_ACCESS_DENIED",
                sendInternal("POST", "/internal/v1/community/relations:batch", svcToken, adminJwt, body));

        AuthenticationAndConfigTest.SESSION_STATE.set("UNAVAILABLE");
        AuthenticationAndConfigTest.setActiveSession("blog", FOLLOWER_A);
        String validViewerJwt = blogToken(FOLLOWER_A);
        AuthenticationAndConfigTest.SESSION_STATE.set("UNAVAILABLE");
        assertInternalError(503, "AUTH_STATE_UNAVAILABLE",
                sendInternal("POST", "/internal/v1/community/relations:batch", svcToken, validViewerJwt, body));

        // 4. Strict request body validation -> 400 INVALID_ARGUMENT
        for (String badJson : List.of(
                "{}",
                "{\"userIds\":[]}",
                "{\"userIds\":[42]}",
                "{\"userIds\":[\"0\"]}",
                "{\"userIds\":[\"-1\"]}",
                "{\"userIds\":[\"01\"]}",
                "{\"userIds\":[\"42\"],\"viewerId\":\"1\"}",
                JSON.writeValueAsString(Map.of("userIds", Collections.nCopies(101, "42"))))) {
            assertInternalError(400, "INVALID_ARGUMENT",
                    sendInternal("POST", "/internal/v1/community/relations:batch", svcToken, null, badJson));
        }
        assertInternalError(400, "INVALID_ARGUMENT",
                sendInternal("POST", "/internal/v1/community/relations:batch?x=1", svcToken, null, "{\"userIds\":[\"42\"]}"));
        assertInternalError(401, "INVALID_SERVICE_TOKEN",
                sendInternal("GET", "/internal/v1/community/relations:batch", svcToken, null, null));

        // 5. DB failure -> 503 DEPENDENCY_UNAVAILABLE
        db.execute("DROP TABLE yan_user_follow");
        assertInternalError(503, "DEPENDENCY_UNAVAILABLE",
                sendInternal("POST", "/internal/v1/community/relations:batch", svcToken, null, "{\"userIds\":[\"42\"]}"));
    }

    @Test
    void eligibilityIsSharedByBothListsAndBothCountsBeforePaging() throws Exception {
        List<String> storedOrder = List.of("41", MISSING_USER, "42", BANNED_USER, FOLLOWEE_B, DELETED_USER);
        List<String> eligibleOrder = List.of(FOLLOWEE_B, "42", "41");
        for (String counterpart : storedOrder) {
            insertFollow(FOLLOWER_A, counterpart);
            insertFollow(counterpart, FOLLOWER_A);
        }
        String viewer = blogToken(FOLLOWER_A);
        assertPage("/api/blog/follow/following?pageSize=2", viewer, "3", eligibleOrder.subList(0, 2));
        assertPage("/api/blog/follow/following?pageNum=2&pageSize=2", viewer, "3", eligibleOrder.subList(2, 3));
        assertPage("/api/blog/follow/followers?pageSize=2", viewer, "3", eligibleOrder.subList(0, 2));
        assertPage("/api/blog/follow/followers?pageNum=2&pageSize=2", viewer, "3", eligibleOrder.subList(2, 3));
        assertPage("/api/blog/follow/" + FOLLOWER_A + "/following?pageNum=3&pageSize=2", null, "3", List.of());

        VALIDITY_BATCH_SIZES.clear();
        var res = relations(List.of(FOLLOWER_A, BANNED_USER), viewer);
        assertEquals(200, res.statusCode(), res.body());
        var items = JSON.readTree(res.body()).path("items");
        assertExactRelationItem(items.get(0), FOLLOWER_A, "3", "3", null);
        // Actual relation status remains available even for an ineligible counterparty.
        assertExactRelationItem(items.get(1), BANNED_USER, "1", "1", true);
        assertEquals(List.of(7), VALIDITY_BATCH_SIZES);
        assertEquals(12, count("yan_user_follow"));
    }

    @Test
    void batchesDistinctIdsAndFailsEntireReadOnLateUserFailureWithoutBlockingUnfollow() throws Exception {
        for (int offset = 1; offset <= 102; offset++) {
            insertFollow(FOLLOWER_A, Long.toString(10_000L + offset));
        }
        insertFollow(FOLLOWER_A, BANNED_USER);
        insertFollow(FOLLOWER_A, FOLLOWEE_B);
        String viewer = blogToken(FOLLOWER_A);
        assertPage("/api/blog/follow/following?pageNum=2&pageSize=100", viewer, "103",
                List.of("10003", "10002", "10001"));
        assertEquals(List.of(4, 100), VALIDITY_BATCH_SIZES.stream().sorted().toList());

        for (String mode : List.of("partial-second-batch", "down-second-batch", "profile-missing")) {
            VALIDITY_BATCH_SIZES.clear();
            USER_SERVER_MODE.set(mode);
            var response = send("GET", "/api/blog/follow/following", viewer);
            assertEquals(503, response.statusCode(), response.body());
            JsonNode error = JSON.readTree(response.body());
            assertEquals(500, error.path("code").asInt());
            assertFalse(error.has("data"));
            assertTrue(VALIDITY_BATCH_SIZES.contains(4), mode);
        }
        USER_SERVER_MODE.set("down");
        assertInternalError(503, "DEPENDENCY_UNAVAILABLE", relations(List.of(FOLLOWER_A), null));
        int before = VALIDITY_CALLS.get();
        var cancelled = send("DELETE", "/api/blog/follow/" + BANNED_USER, viewer);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals(200, JSON.readTree(cancelled.body()).path("code").asInt());
        assertEquals(before, VALIDITY_CALLS.get());
        assertEquals(103, count("yan_user_follow"));
    }

    @Test
    void admitsMoreThanFiveHundredEffectiveAccountsForListsAndBothCounts() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (long i = 1; i <= 950; i++) {
            long counterpart = 9007199254742000L + i;
            rows.add(new Object[]{Long.parseLong(FOLLOWER_A), counterpart});
            rows.add(new Object[]{counterpart, Long.parseLong(FOLLOWER_A)});
        }
        db.batchUpdate("INSERT INTO yan_user_follow(follower_id, followee_id) VALUES (?, ?)", rows);

        USER_SERVER_MODE.set("overlap");
        assertPage("/follow/following?pageNum=10&pageSize=100", blogToken(FOLLOWER_A), "950",
                java.util.stream.LongStream.iterate(9007199254742050L, n -> n - 1)
                        .limit(50).mapToObj(Long::toString).toList());
        List<Integer> sortedSizes = List.of(50, 100, 100, 100, 100, 100, 100, 100, 100, 100);
        assertEquals(sortedSizes, VALIDITY_BATCH_SIZES.stream().sorted().toList());
        assertTrue(OVERLAP_PEAK.get() >= 2 && OVERLAP_PEAK.get() <= 8,
                "Expected overlapping validity requests bounded by 8, observed " + OVERLAP_PEAK.get());

        VALIDITY_BATCH_SIZES.clear();
        var response = relations(List.of(FOLLOWER_A), null);
        assertEquals(200, response.statusCode(), response.body());
        assertExactRelationItem(JSON.readTree(response.body()).path("items").get(0),
                FOLLOWER_A, "950", "950", null);
        assertEquals(sortedSizes, VALIDITY_BATCH_SIZES.stream().sorted().toList());
    }

    @Test
    void configuredScanCapacityFailsWholeReadBeforeCallingUser() throws Exception {
        String sql = "INSERT INTO yan_user_follow(follower_id, followee_id) VALUES (?, ?)";
        List<Object[]> tooManyEdges = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        for (long i = 1; i <= 66; i++) {
            subjects.add(Long.toString(50_000L + i));
            for (long j = 1; j <= 49; j++) {
                tooManyEdges.add(new Object[]{50_000L + i, 60_000L + j});
            }
        }
        // 3,234 edges exceed the configured 3,200 even with only 49 distinct counterparts.
        db.batchUpdate(sql, tooManyEdges);
        assertInternalError(503, "DEPENDENCY_UNAVAILABLE", relations(subjects, null));
        assertEquals(0, VALIDITY_CALLS.get());
    }

    @Test
    void cumulativeValidityBatchesShareOneDeadline() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (long i = 1; i <= 3200; i++) {
            rows.add(new Object[]{Long.parseLong(FOLLOWER_A), 20_000L + i});
        }
        db.batchUpdate("INSERT INTO yan_user_follow(follower_id, followee_id) VALUES (?, ?)", rows);
        USER_SERVER_MODE.set("slow-validity");
        var response = send("GET", "/api/blog/follow/following", blogToken(FOLLOWER_A));
        assertEquals(503, response.statusCode(), response.body());
        assertFalse(JSON.readTree(response.body()).has("data"));
        assertEquals(0, PROFILE_CALLS.get());
    }

    private void assertPage(String path, String viewer, String expectedTotal, List<String> expectedIds) throws Exception {
        var response = send("GET", path, viewer);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = JSON.readTree(response.body()).path("data");
        assertEquals(expectedTotal, data.path("total").textValue());
        List<String> ids = new ArrayList<>();
        data.path("rows").forEach(row -> ids.add(row.path("userId").textValue()));
        assertEquals(expectedIds, ids);
    }

    private HttpResponse<String> relations(List<String> ids, String viewer) throws Exception {
        String serviceToken = AuthenticationAndConfigTest.serviceToken(
                "kid-1", AuthenticationAndConfigTest.PRIMARY_KEYS, "knode-content", "knode-community",
                List.of("community:relations:read"));
        return sendInternal("POST", "/internal/v1/community/relations:batch", serviceToken, viewer,
                JSON.writeValueAsString(Map.of("userIds", ids)));
    }

    private void insertFollow(String from, String to) {
        db.update("INSERT INTO yan_user_follow(follower_id, followee_id) VALUES (?, ?)",
                Long.parseLong(from), Long.parseLong(to));
    }

    private static void assertExactRelationItem(JsonNode item, String userId, String following, String followers, Boolean viewerFollowing) {
        Set<String> names = Set.of("userId", "followingCount", "followerCount", "viewerFollowing");
        assertEquals(4, item.size());
        names.forEach(k -> assertTrue(item.has(k), "Missing key " + k));
        assertEquals(userId, item.get("userId").textValue());
        assertEquals(following, item.get("followingCount").textValue());
        assertEquals(followers, item.get("followerCount").textValue());
        if (viewerFollowing == null) {
            assertTrue(item.get("viewerFollowing").isNull());
        } else {
            assertTrue(item.get("viewerFollowing").isBoolean());
            assertEquals(viewerFollowing, item.get("viewerFollowing").booleanValue());
        }
    }

    private static void assertInternalError(int expectedStatus, String expectedCode, HttpResponse<String> resp) throws Exception {
        assertEquals(expectedStatus, resp.statusCode(), resp.body());
        assertEquals("no-store", resp.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode root = JSON.readTree(resp.body());
        assertEquals(expectedCode, root.path("error").path("code").asText());
        assertTrue(root.path("requestId").isTextual());
    }

    private int count(String table) {
        Integer c = db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return c == null ? 0 : c;
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

    private HttpResponse<String> sendInternal(String method, String path, String bearer, String viewerToken, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (viewerToken != null) b.header("token", viewerToken);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return AuthenticationAndConfigTest.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startUserServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXEC);
            server.createContext("/internal/v1/auth/service-tokens", exchange -> {
                try {
                    TOKEN_ISSUE_CALLS.incrementAndGet();
                    if ("down".equals(USER_SERVER_MODE.get())) {
                        reply(exchange, 503, "{}");
                        return;
                    }
                    JsonNode req = JSON.readTree(exchange.getRequestBody());
                    String aud = req.path("audience").asText();
                    List<String> perms = new ArrayList<>();
                    req.path("permissions").forEach(n -> perms.add(n.asText()));
                    reply(exchange, 200, JSON.writeValueAsString(Map.of(
                            "accessToken", "svc-" + aud + "-" + TOKEN_ISSUE_CALLS.get(),
                            "tokenType", "Bearer",
                            "expiresIn", 120,
                            "audience", aud,
                            "permissions", perms)));
                } catch (Exception e) {
                    exchange.close();
                }
            });
            server.createContext("/internal/v1/users/validity:batch", exchange -> {
                boolean overlapping = false;
                try {
                    VALIDITY_CALLS.incrementAndGet();
                    String mode = USER_SERVER_MODE.get();
                    if ("down".equals(mode)) {
                        reply(exchange, 503, "{}");
                        return;
                    }
                    if ("malformed-validity".equals(mode)) {
                        reply(exchange, 200, "{\"items\":[{\"userId\":\"42\",\"exists\":false,\"active\":true}]}");
                        return;
                    }
                    JsonNode req = JSON.readTree(exchange.getRequestBody());
                    JsonNode userIds = req.path("userIds");
                    if (!userIds.isArray() || userIds.isEmpty() || userIds.size() > 100
                            || !"FOLLOW_TARGET".equals(req.path("purpose").asText())) {
                        reply(exchange, 400, "{}");
                        return;
                    }
                    VALIDITY_BATCH_SIZES.add(userIds.size());
                    if ("down-second-batch".equals(mode) && userIds.size() < 100) {
                        reply(exchange, 503, "{}");
                        return;
                    }
                    if ("slow-validity".equals(mode)) {
                        Thread.sleep(850);
                    }
                    if ("overlap".equals(mode)) {
                        overlapping = true;
                        OVERLAP_PEAK.accumulateAndGet(OVERLAP_ACTIVE.incrementAndGet(), Math::max);
                        Thread.sleep(75);
                    }
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (JsonNode idNode : req.path("userIds")) {
                        String id = idNode.asText();
                        if (DELETED_USER.equals(id) || MISSING_USER.equals(id)) {
                            items.add(Map.of("userId", id, "exists", false, "active", false));
                        } else if (BANNED_USER.equals(id)) {
                            items.add(Map.of("userId", id, "exists", true, "active", false));
                        } else {
                            items.add(Map.of("userId", id, "exists", true, "active", true));
                        }
                    }
                    if ("partial-second-batch".equals(mode) && userIds.size() < 100) {
                        items.remove(items.size() - 1);
                    }
                    reply(exchange, 200, JSON.writeValueAsString(Map.of("items", items)));
                } catch (Exception e) {
                    exchange.close();
                } finally {
                    if (overlapping) {
                        OVERLAP_ACTIVE.decrementAndGet();
                    }
                }
            });
            server.createContext("/internal/v1/users/public-profiles:batch", exchange -> {
                try {
                    PROFILE_CALLS.incrementAndGet();
                    if ("down".equals(USER_SERVER_MODE.get())) {
                        reply(exchange, 503, "{}");
                        return;
                    }
                    JsonNode req = JSON.readTree(exchange.getRequestBody());
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (JsonNode idNode : req.path("userIds")) {
                        String id = idNode.asText();
                        if (DELETED_USER.equals(id) || "profile-missing".equals(USER_SERVER_MODE.get())) {
                            items.add(Map.of("userId", id, "status", "MISSING"));
                        } else {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("userId", id);
                            m.put("status", "FOUND");
                            m.put("nickName", FOLLOWEE_B.equals(id) ? "Author B" : "User " + id);
                            m.put("avatar", "https://example.invalid/" + id + ".png");
                            m.put("profileRevision", "1");
                            m.put("updatedAt", "2026-11-24T00:00:00Z");
                            items.add(m);
                        }
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
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
