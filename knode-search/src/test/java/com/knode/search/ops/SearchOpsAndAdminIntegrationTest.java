package com.knode.search.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.search.KnodeSearchApplication;
import com.knode.search.client.ServiceTokenClient;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.model.QueryBudget;
import com.knode.search.support.LoopbackContentSnapshotStub;
import com.knode.search.support.LoopbackRedisStub;
import com.knode.search.support.LoopbackUserAdminAuthStub;
import com.knode.search.support.StatefulElasticsearchStub;
import com.knode.search.support.TestJwtSigner;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {KnodeSearchApplication.class, SearchOpsAndAdminIntegrationTest.TestTokenConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SearchOpsAndAdminIntegrationTest {
    private static final String ENV = "it-search04";
    private static final String ES_ALIAS = "knode_it_search04_articles_search";
    private static final String STREAM_KEY = "knode:it-search04:v1:events:content";
    private static final String CONSUMER_GROUP = "knode-search-v1";
    private static final String DB_NAME = "search_ops04_" + UUID.randomUUID().toString().replace("-", "");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestJwtSigner userJwtSigner;
    private static TestJwtSigner serviceJwtSigner;
    private static LoopbackRedisStub redisStub;
    private static StatefulElasticsearchStub esStub;
    private static LoopbackContentSnapshotStub contentStub;
    private static LoopbackUserAdminAuthStub userAuthStub;
    private static volatile boolean schemaInitialized = false;

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("searchConsumerDataSource")
    private DataSource consumerDataSource;

    @Autowired
    @Qualifier("searchConsumerJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SearchConsumerRepository consumerRepository;

    @Autowired
    private SearchBacklogMetricsBinder backlogMetricsBinder;

    @Autowired
    private MeterRegistry meterRegistry;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startStubs() throws Exception {
        userJwtSigner = TestJwtSigner.generate("knode-user-k1");
        serviceJwtSigner = TestJwtSigner.generate("knode-svc-k1");
        redisStub = new LoopbackRedisStub();
        esStub = new StatefulElasticsearchStub();
        contentStub = new LoopbackContentSnapshotStub();
        contentStub.setExpectedBearerToken("search04-content-token");
        userAuthStub = new LoopbackUserAdminAuthStub();
    }

    @AfterAll
    static void stopStubs() throws Exception {
        if (userAuthStub != null) {
            userAuthStub.close();
        }
        if (contentStub != null) {
            contentStub.close();
        }
        if (esStub != null) {
            esStub.close();
        }
        if (redisStub != null) {
            redisStub.close();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("knode.environment", () -> ENV);
        registry.add("knode.search.elasticsearch.url", () -> esStub.baseUrl());
        registry.add("knode.search.elasticsearch.index", () -> ES_ALIAS);
        registry.add("knode.search.clients.content-origin", () -> contentStub.baseUrl());
        registry.add("knode.search.clients.user-origin", () -> userAuthStub.baseUrl());
        registry.add("knode.search.clients.content-snapshot-timeout-ms", () -> "5000");

        registry.add("knode.search.cache.enabled", () -> "true");
        registry.add("knode.search.cache.redis.host", () -> "127.0.0.1");
        registry.add("knode.search.cache.redis.port", () -> Integer.toString(redisStub.port()));

        registry.add("knode.search.consumer.enabled", () -> "true");
        registry.add("knode.search.consumer.datasource.url", () ->
                "jdbc:h2:mem:" + DB_NAME
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("knode.search.consumer.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("knode.search.consumer.datasource.username", () -> "sa");
        registry.add("knode.search.consumer.datasource.password", () -> "");

        registry.add("knode.search.consumer.stream.enabled", () -> "true");
        registry.add("knode.search.consumer.stream.worker-enabled", () -> "false");
        registry.add("knode.search.consumer.stream.redis.host", () -> "127.0.0.1");
        registry.add("knode.search.consumer.stream.redis.port", () -> Integer.toString(redisStub.port()));
        registry.add("knode.search.consumer.stream.stream-key", () -> STREAM_KEY);
        registry.add("knode.search.consumer.stream.group-name", () -> CONSUMER_GROUP);

        registry.add("knode.search.consumer.indexer.enabled", () -> "true");
        registry.add("knode.search.consumer.indexer.worker-enabled", () -> "false");

        registry.add("knode.search.rebuild.enabled", () -> "true");
        registry.add("knode.search.rebuild.worker-enabled", () -> "false");
        registry.add("knode.search.rebuild.instance-id", () -> "ops04-inst-1");

        registry.add("knode.search.auth.expected-service-audience", () -> "knode-search");
        registry.add("knode.search.auth.trusted-public-keys.knode-user-k1", () -> userJwtSigner.publicKeyPem());
        registry.add("knode.search.auth.service-trusted-public-keys.knode-svc-k1", () -> serviceJwtSigner.publicKeyPem());
        registry.add("knode.search.auth.session.redis.enabled", () -> "true");
        registry.add("knode.search.auth.session.redis.host", () -> "127.0.0.1");
        registry.add("knode.search.auth.session.redis.port", () -> Integer.toString(redisStub.port()));
    }

    @BeforeEach
    void resetState() {
        if (!schemaInitialized) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/search-consumer-schema.sql"));
            populator.execute(consumerDataSource);
            schemaInitialized = true;
        }

        jdbcTemplate.execute("DELETE FROM search_ops_replay_audit");
        jdbcTemplate.execute("DELETE FROM search_rebuild_job");
        jdbcTemplate.execute("DELETE FROM search_index_task");
        jdbcTemplate.execute("DELETE FROM search_article_projection");
        jdbcTemplate.execute("DELETE FROM search_article_version_barrier");
        jdbcTemplate.execute("DELETE FROM search_event_inbox");
        jdbcTemplate.execute("DELETE FROM search_event_failure");
        jdbcTemplate.execute("DELETE FROM search_index_generation");

        long now = System.currentTimeMillis();
        jdbcTemplate.update("""
                INSERT INTO search_index_generation (
                    alias_name, active_generation, active_physical_index,
                    require_cold_visibility_guard, cutover_state,
                    active_cache_invalidation_status, active_cache_invalidation_attempts, updated_at_ms
                ) VALUES (?, 1, ?, 0, 'NORMAL', 'DONE', 0, ?)
                """, ES_ALIAS, ES_ALIAS + "_g1", now);

        redisStub.reset();
        redisStub.createGroup(STREAM_KEY, CONSUMER_GROUP);
        esStub.reset();
        esStub.setAlias(ES_ALIAS, ES_ALIAS + "_g1");
        contentStub.clearInterceptor();
        contentStub.clearRequests();
        contentStub.clearCurrentArticles();
        contentStub.setLivePublicArticles(List.of());
        userAuthStub.resetCalls();
        userAuthStub.setAuthority("1", List.of("admin"), List.of("*:*:*"));
    }

    @Test
    void internalOpsAuthRejectsForgedHeadersWrongAudWrongScopeAndAllowsValidServiceTokens() throws Exception {
        // 1. Missing token -> 401
        HttpResponse<String> noAuth = send("GET", "/internal/v1/ops/consumers/summary", null, Map.of());
        assertThat(noAuth.statusCode()).isEqualTo(401);
        assertThat(noAuth.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(MAPPER.readTree(noAuth.body()).path("error").path("code").asText()).isEqualTo("INVALID_SERVICE_TOKEN");

        String readToken = serviceJwtSigner.signServiceToken("knode-content", "knode-search", List.of("ops:read"), 120);
        String retryToken = serviceJwtSigner.signServiceToken("knode-content", "knode-search", List.of("ops:retry"), 120);
        String wrongAud = serviceJwtSigner.signServiceToken("knode-content", "knode-community", List.of("ops:read"), 120);
        String userJwt = userJwtSigner.signUserToken("1", UUID.randomUUID().toString(), "admin", 600);

        // 2. Forged X-User-Id or viewer token header rejected even with valid service token
        assertThat(send("GET", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + readToken, "X-User-Id", "1")).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + readToken, "X-Internal-Actor", "root")).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + readToken, "token", userJwt)).statusCode()).isEqualTo(401);

        // 3. Duplicate Authorization headers rejected
        HttpRequest dupAuth = HttpRequest.newBuilder(uri("/internal/v1/ops/consumers/summary"))
                .header("Authorization", "Bearer " + readToken)
                .header("Authorization", "Bearer " + readToken)
                .GET().build();
        assertThat(httpClient.send(dupAuth, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        // 4. Wrong audience & user JWT rejected
        assertThat(send("GET", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + wrongAud)).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + userJwt)).statusCode()).isEqualTo(401);

        // 5. Permission separation: ops:read can GET summary/events, cannot POST retry (403); ops:retry vice versa
        assertThat(send("GET", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + readToken)).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/internal/v1/ops/consumers/retry", "{\"replayRequestId\":\"r1\"}",
                Map.of("Authorization", "Bearer " + readToken, "Content-Type", "application/json")).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + retryToken)).statusCode()).isEqualTo(403);

        // 6. HEAD rejected: 401 when unauthenticated, 405 when authenticated
        assertThat(send("HEAD", "/internal/v1/ops/consumers/summary", null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(send("HEAD", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + readToken)).statusCode()).isEqualTo(405);

        // 7. Unknown field in POST retry rejected with 400 INVALID_ARGUMENT
        HttpResponse<String> badField = send("POST", "/internal/v1/ops/consumers/retry",
                "{\"replayRequestId\":\"r-bad\",\"extra\":\"nope\"}",
                Map.of("Authorization", "Bearer " + retryToken, "Content-Type", "application/json"));
        assertThat(badField.statusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(badField.body()).path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENT");

        // 8. Public search remains unaffected and requires no authentication
        HttpResponse<String> publicSearch = send("GET", "/api/blog/search?keyword=test", null, Map.of());
        assertThat(publicSearch.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(publicSearch.body()).path("code").asInt()).isEqualTo(200);
    }

    @Test
    void internalOpsSummaryEventsAndIdempotentLeaseSafeBatchAndSingleRetry() throws Exception {
        long now = System.currentTimeMillis();
        String evtPending = UUID.randomUUID().toString();
        String evtActiveLease = UUID.randomUUID().toString();
        String evtExpiredLease = UUID.randomUUID().toString();
        String evtDead = UUID.randomUUID().toString();
        String evtDone = UUID.randomUUID().toString();
        String evtIgnored = UUID.randomUUID().toString();
        String evtPoison = UUID.randomUUID().toString();

        insertInbox(evtPending, "101", "APPLIED", now - 5000);
        insertTask(101L, "PENDING", 2, now + 60_000, null, "NONE", "ERR_TEMP", now - 5000);

        insertInbox(evtActiveLease, "102", "APPLIED", now - 4000);
        insertTask(102L, "LEASED", 1, now - 4000, now + 60_000, "NONE", null, now - 4000);

        insertInbox(evtExpiredLease, "103", "APPLIED", now - 3000);
        insertTask(103L, "LEASED", 1, now - 10_000, now - 1000, "NONE", "ERR_CRASH", now - 3000);

        insertInbox(evtDead, "104", "APPLIED", now - 2000);
        insertTask(104L, "DEAD", 5, now - 2000, null, "NONE", "ERR_ES_503", now - 2000);

        insertInbox(evtDone, "105", "APPLIED", now - 1000);
        insertTask(105L, "DONE", 0, now - 1000, null, "PENDING", null, now - 1000);

        insertInbox(evtIgnored, "106", "IGNORED", now - 500);

        jdbcTemplate.update("""
                INSERT INTO search_event_failure (
                    failure_key, event_id, stream_record_id, error_code, error_message,
                    payload_sha256, payload_bytes, recorded_at_ms
                ) VALUES ('fail-1', ?, '9-0', 'BAD_SCHEMA', 'malformed payload', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 42, ?)
                """, evtPoison, now - 100);

        String fullOpsToken = serviceJwtSigner.signServiceToken(
                "knode-content", "knode-search", List.of("ops:read", "ops:retry"), 120);

        // Summary check (decimal strings + stream AVAILABLE)
        HttpResponse<String> summaryResp = send("GET", "/internal/v1/ops/consumers/summary", null,
                Map.of("Authorization", "Bearer " + fullOpsToken));
        assertThat(summaryResp.statusCode()).isEqualTo(200);
        JsonNode summary = MAPPER.readTree(summaryResp.body());
        assertThat(summary.path("domain").asText()).isEqualTo("search");
        assertThat(summary.path("indexTasks").path("PENDING").asText()).isEqualTo("1");
        assertThat(summary.path("indexTasks").path("LEASED").asText()).isEqualTo("2");
        assertThat(summary.path("indexTasks").path("expiredLeased").asText()).isEqualTo("1");
        assertThat(summary.path("indexTasks").path("DEAD").asText()).isEqualTo("1");
        assertThat(summary.path("indexTasks").path("DONE").asText()).isEqualTo("1");
        assertThat(summary.path("indexTasks").path("backlog").asText()).isEqualTo("4");
        assertThat(summary.path("inbox").path("PROTOCOL_FAILED").asText()).isEqualTo("1");
        assertThat(summary.path("cacheCompensation").path("taskPending").asText()).isEqualTo("1");
        assertThat(summary.path("stream").path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(summary.path("stream").path("pendingCount").asText()).isEqualTo("0");

        // Verify Micrometer backlog gauges reflect exact live counts
        backlogMetricsBinder.refreshNow();
        assertThat(meterRegistry.get("knode.search.index.tasks.pending").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("knode.search.index.tasks.leased").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("knode.search.index.tasks.expired_leased").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("knode.search.index.tasks.dead").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("knode.search.inbox.protocol_failed").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("knode.search.cache.compensation.pending").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("knode.search.stream.pending").gauge().value()).isEqualTo(0.0);

        // Events pagination & filtering
        HttpResponse<String> deadEventsResp = send("GET", "/internal/v1/ops/consumers/events?status=DEAD&pageNum=1&pageSize=10",
                null, Map.of("Authorization", "Bearer " + fullOpsToken));
        assertThat(deadEventsResp.statusCode()).isEqualTo(200);
        JsonNode deadEvents = MAPPER.readTree(deadEventsResp.body());
        assertThat(deadEvents.path("total").asText()).isEqualTo("2");
        JsonNode deadTaskRow = null;
        for (JsonNode r : deadEvents.path("rows")) {
            if ("INDEX_TASK".equals(r.path("recordType").asText())) {
                deadTaskRow = r;
            }
        }
        assertThat(deadTaskRow).isNotNull();
        assertThat(deadTaskRow.path("eventId").asText()).isEqualTo(evtDead);
        assertThat(deadTaskRow.path("articleId").asText()).isEqualTo("104");
        assertThat(deadTaskRow.path("retryCount").asText()).isEqualTo("5");

        HttpResponse<String> poisonEventsResp = send("GET", "/internal/v1/ops/consumers/events?status=PROTOCOL_FAILED",
                null, Map.of("Authorization", "Bearer " + fullOpsToken));
        assertThat(poisonEventsResp.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(poisonEventsResp.body()).path("total").asText()).isEqualTo("1");

        assertThat(send("GET", "/internal/v1/ops/consumers/events?status=UNKNOWN_STATUS",
                null, Map.of("Authorization", "Bearer " + fullOpsToken)).statusCode()).isEqualTo(400);

        // Single retry guardrails BEFORE batch retry clears DEAD:
        // a) Non-UUID -> 400
        assertThat(send("POST", "/internal/v1/ops/consumers/not-a-uuid/retry",
                "{\"replayRequestId\":\"req-bad-id\"}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json")).statusCode()).isEqualTo(400);
        // b) Unknown UUID -> 404
        assertThat(send("POST", "/internal/v1/ops/consumers/" + UUID.randomUUID() + "/retry",
                "{\"replayRequestId\":\"req-404\"}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json")).statusCode()).isEqualTo(404);
        // c) Poison event -> 409 STATE_CONFLICT
        HttpResponse<String> poisonRetry = send("POST", "/internal/v1/ops/consumers/" + evtPoison + "/retry",
                "{\"replayRequestId\":\"req-poison\"}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(poisonRetry.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(poisonRetry.body()).path("error").path("code").asText()).isEqualTo("STATE_CONFLICT");
        // d) Active leased event -> 409 STATE_CONFLICT
        HttpResponse<String> activeRetry = send("POST", "/internal/v1/ops/consumers/" + evtActiveLease + "/retry",
                "{\"replayRequestId\":\"req-active\"}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(activeRetry.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(activeRetry.body()).path("error").path("code").asText()).isEqualTo("STATE_CONFLICT");
        // e) IGNORED event -> 200 NOOP
        HttpResponse<String> ignoredRetry = send("POST", "/internal/v1/ops/consumers/" + evtIgnored + "/retry",
                "{\"replayRequestId\":\"req-ignored\"}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(ignoredRetry.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(ignoredRetry.body()).path("status").asText()).isEqualTo("NOOP");
        assertThat(MAPPER.readTree(ignoredRetry.body()).path("requeued").asText()).isEqualTo("0");

        // f) Single DEAD event retry -> 200 ACCEPTED + idempotent repeat + 409 conflict on different target
        HttpResponse<String> deadRetry1 = send("POST", "/internal/v1/ops/consumers/" + evtDead + "/retry",
                "{\"replayRequestId\":\"req-single-dead\",\"operator\":\"ops-1\"}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(deadRetry1.statusCode()).isEqualTo(200);
        JsonNode deadRetryJson = MAPPER.readTree(deadRetry1.body());
        assertThat(deadRetryJson.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(deadRetryJson.path("requeued").asText()).isEqualTo("1");
        SearchIndexTaskRecord task104 = consumerRepository.findIndexTask(1L, 104L).orElseThrow();
        assertThat(task104.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(task104.attempts()).isEqualTo(0);

        // Repeat identical replayRequestId returns exact cached response
        HttpResponse<String> deadRetryRepeat = send("POST", "/internal/v1/ops/consumers/" + evtDead + "/retry",
                "{\"replayRequestId\":\"req-single-dead\",\"operator\":\"ops-1\"}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(deadRetryRepeat.statusCode()).isEqualTo(200);
        assertThat(deadRetryRepeat.body()).isEqualTo(deadRetry1.body());

        // Same replayRequestId with different eventId -> 409 IDEMPOTENCY_CONFLICT
        HttpResponse<String> idempConflict = send("POST", "/internal/v1/ops/consumers/" + evtIgnored + "/retry",
                "{\"replayRequestId\":\"req-single-dead\",\"operator\":\"ops-1\"}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(idempConflict.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(idempConflict.body()).path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // Batch retry: requeues backoff PENDING (101) + expired LEASED (103), skips active LEASED (102), expedites cache comp (105)
        HttpResponse<String> batchResp = send("POST", "/internal/v1/ops/consumers/retry",
                "{\"replayRequestId\":\"req-batch-1\",\"operator\":\"ops-batch\",\"limit\":10}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(batchResp.statusCode()).isEqualTo(200);
        JsonNode batchJson = MAPPER.readTree(batchResp.body());
        assertThat(batchJson.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(batchJson.path("requeued").asText()).isEqualTo("2");
        assertThat(batchJson.path("skippedActiveLease").asText()).isEqualTo("1");
        assertThat(batchJson.path("cacheCompensationsExpedited").asText()).isEqualTo("1");

        // Active lease 102 must remain LEASED and untouched
        SearchIndexTaskRecord task102 = consumerRepository.findIndexTask(1L, 102L).orElseThrow();
        assertThat(task102.status()).isEqualTo(SearchIndexTaskStatus.LEASED);
        // Expired lease 103 moved back to PENDING
        assertThat(consumerRepository.findIndexTask(1L, 103L).orElseThrow().status())
                .isEqualTo(SearchIndexTaskStatus.PENDING);

        // Idempotent repeat of batch retry & mismatch conflict
        HttpResponse<String> batchRepeat = send("POST", "/internal/v1/ops/consumers/retry",
                "{\"replayRequestId\":\"req-batch-1\",\"operator\":\"ops-batch\",\"limit\":10}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(batchRepeat.statusCode()).isEqualTo(200);
        assertThat(batchRepeat.body()).isEqualTo(batchResp.body());

        HttpResponse<String> batchMismatch = send("POST", "/internal/v1/ops/consumers/retry",
                "{\"replayRequestId\":\"req-batch-1\",\"operator\":\"ops-batch\",\"limit\":5}",
                Map.of("Authorization", "Bearer " + fullOpsToken, "Content-Type", "application/json"));
        assertThat(batchMismatch.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(batchMismatch.body()).path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void adminAuthEnforcesSessionFreshMeFailClosedPermissionsAndDualPaths() throws Exception {
        String sid = UUID.randomUUID().toString();
        String adminToken = userJwtSigner.signUserToken("1", sid, "admin", 600);
        storeSession("1", sid, "admin", "ACTIVE");

        // 1. Missing token -> 401
        HttpResponse<String> noToken = send("POST", "/api/admin/content/search/retry", null, Map.of());
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(MAPPER.readTree(noToken.body()).path("code").asInt()).isEqualTo(401);

        // 2. Machine Authorization Bearer rejected on admin route
        String svcToken = serviceJwtSigner.signServiceToken("knode-content", "knode-search", List.of("ops:retry"), 120);
        assertThat(send("POST", "/api/admin/content/search/retry", null,
                Map.of("Authorization", "Bearer " + svcToken)).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/admin/content/search/retry", null,
                Map.of("token", adminToken, "Authorization", "Bearer " + svcToken)).statusCode()).isEqualTo(401);

        // 3. Forged identity header rejected
        assertThat(send("POST", "/api/admin/content/search/retry", null,
                Map.of("token", adminToken, "X-User-Id", "1")).statusCode()).isEqualTo(401);

        // 4. blog scope rejected with 403
        String blogSid = UUID.randomUUID().toString();
        String blogToken = userJwtSigner.signUserToken("2", blogSid, "blog", 600);
        storeSession("2", blogSid, "blog", "ACTIVE");
        assertThat(send("POST", "/api/admin/content/search/retry", null,
                Map.of("token", blogToken)).statusCode()).isEqualTo(403);

        // 5. Missing/revoked session -> 401
        String missingSid = UUID.randomUUID().toString();
        String missingSessionToken = userJwtSigner.signUserToken("1", missingSid, "admin", 600);
        assertThat(send("POST", "/api/admin/content/search/retry", null,
                Map.of("token", missingSessionToken)).statusCode()).isEqualTo(401);

        // 6. Non-wildcard admin (roles=[admin], permissions=[] or unrelated permissions) -> FAIL-CLOSED 403!
        userAuthStub.setAuthority("1", List.of("admin"), List.of());
        assertThat(send("POST", "/api/admin/content/search/rebuild", null,
                Map.of("token", adminToken)).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/content/search/retry", null,
                Map.of("token", adminToken)).statusCode()).isEqualTo(403);

        userAuthStub.setAuthority("1", List.of("admin"), List.of("content:article:edit", "ops:outbox:retry"));
        assertThat(send("POST", "/api/admin/content/search/sync/501", null,
                Map.of("token", adminToken)).statusCode()).isEqualTo(403);

        // 7. Explicit DB-backed *:*:* grants access on BOTH /api/admin/content/search/* and /content/search/*
        userAuthStub.resetCalls();
        userAuthStub.setAuthority("1", List.of("admin"), List.of("*:*:*"));
        HttpResponse<String> ok1 = send("POST", "/api/admin/content/search/retry", null, Map.of("token", adminToken));
        HttpResponse<String> ok2 = send("POST", "/content/search/retry", null, Map.of("token", adminToken));
        assertThat(ok1.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(ok1.body()).path("code").asInt()).isEqualTo(200);
        assertThat(ok2.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(ok2.body()).path("code").asInt()).isEqualTo(200);
        // Proves fresh /api/admin/auth/me call on every single request (zero caching)
        assertThat(userAuthStub.callCount()).isEqualTo(2);

        // Immediately revoking *:*:* blocks the very next request with 403
        userAuthStub.setAuthority("1", List.of("admin"), List.of());
        assertThat(send("POST", "/api/admin/content/search/retry", null, Map.of("token", adminToken)).statusCode())
                .isEqualTo(403);

        // 8. Subject mismatch between token/session and /api/admin/auth/me -> HTTP 503 (never 401/200)
        userAuthStub.setAuthority("999", List.of("admin"), List.of("*:*:*"));
        HttpResponse<String> mismatch = send("POST", "/api/admin/content/search/retry", null, Map.of("token", adminToken));
        assertThat(mismatch.statusCode()).isEqualTo(503);
        assertThat(MAPPER.readTree(mismatch.body()).path("code").asInt()).isEqualTo(500);

        // 9. User /api/admin/auth/me outage -> HTTP 503 (never 401)
        userAuthStub.setHandler(req -> LoopbackUserAdminAuthStub.status(503, "{\"code\":500,\"msg\":\"down\"}"));
        HttpResponse<String> userDown = send("POST", "/api/admin/content/search/retry", null, Map.of("token", adminToken));
        assertThat(userDown.statusCode()).isEqualTo(503);
        assertThat(MAPPER.readTree(userDown.body()).path("code").asInt()).isEqualTo(500);

        // 10. Redis session store error -> HTTP 503 (never 401)
        userAuthStub.setAuthority("1", List.of("admin"), List.of("*:*:*"));
        redisStub.failCommand("GET", "ERR redis unavailable");
        try {
            HttpResponse<String> redisDown = send("POST", "/api/admin/content/search/retry", null, Map.of("token", adminToken));
            assertThat(redisDown.statusCode()).isEqualTo(503);
            assertThat(MAPPER.readTree(redisDown.body()).path("code").asInt()).isEqualTo(500);
        } finally {
            redisStub.clearFaults();
        }
    }

    @Test
    void adminRebuildRetryAndSingleArticleSyncRespectBarriersAndUpstreamFailure() throws Exception {
        String sid = UUID.randomUUID().toString();
        String adminToken = userJwtSigner.signUserToken("1", sid, "admin", 600);
        storeSession("1", sid, "admin", "ACTIVE");
        userAuthStub.setAuthority("1", List.of("admin"), List.of("*:*:*"));

        // 1. Admin /rebuild starts a rebuild job and repeated call reuses the active job
        HttpResponse<String> rb1 = send("POST", "/api/admin/content/search/rebuild", null, Map.of("token", adminToken));
        assertThat(rb1.statusCode()).isEqualTo(200);
        JsonNode rb1Data = MAPPER.readTree(rb1.body()).path("data");
        String jobId = rb1Data.path("jobId").asText();
        assertThat(jobId).isNotBlank();
        assertThat(rb1Data.path("reusedActiveJob").asBoolean()).isFalse();

        HttpResponse<String> rb2 = send("POST", "/content/search/rebuild", null, Map.of("token", adminToken));
        assertThat(rb2.statusCode()).isEqualTo(200);
        JsonNode rb2Data = MAPPER.readTree(rb2.body()).path("data");
        assertThat(rb2Data.path("jobId").asText()).isEqualTo(jobId);
        assertThat(rb2Data.path("reusedActiveJob").asBoolean()).isTrue();

        // 2. Invalid articleId on /sync/{articleId} -> HTTP 200 code 515
        HttpResponse<String> badId = send("POST", "/api/admin/content/search/sync/not-numeric", null, Map.of("token", adminToken));
        assertThat(badId.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(badId.body()).path("code").asInt()).isEqualTo(515);

        // 3. Single-article sync PUBLIC v5 -> upserts projection & real ES document
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                501L, 5L, "Synced Public Title", "Sync Summary", "Sync Body Content",
                3L, "Cloud", List.of("Search"), 77L, 2L, "2026-10-12T00:00:00Z"));
        HttpResponse<String> syncPub = send("POST", "/api/admin/content/search/sync/501", null,
                Map.of("token", adminToken, "X-Request-Id", "sync-pub-501"));
        assertThat(syncPub.statusCode()).isEqualTo(200);
        JsonNode syncPubData = MAPPER.readTree(syncPub.body()).path("data");
        assertThat(syncPubData.path("action").asText()).isEqualTo("UPSERT_SYNCED");
        assertThat(syncPubData.path("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(esDocSearchable("501")).isTrue();

        // 4. Single-article sync WITHDRAWN v6 -> tombstones ES & advances delete barrier
        contentStub.putCurrentHiddenArticle(501L, "WITHDRAWN", 6L);
        HttpResponse<String> syncWithdrawn = send("POST", "/content/search/sync/501", null, Map.of("token", adminToken));
        assertThat(syncWithdrawn.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(syncWithdrawn.body()).path("data").path("action").asText()).isEqualTo("TOMBSTONE_SYNCED");
        assertThat(esDocSearchable("501")).isFalse();

        // 5. Stale PUBLIC v5 (or equal-revision PUBLIC v6) must be blocked by delete/version barrier (never resurrects!)
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                501L, 5L, "Stale Old Title", "Old", "Old",
                3L, "Cloud", List.of("Search"), 10L, 1L, "2026-10-12T00:00:00Z"));
        HttpResponse<String> syncStale = send("POST", "/api/admin/content/search/sync/501", null, Map.of("token", adminToken));
        assertThat(syncStale.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(syncStale.body()).path("data").path("action").asText()).isEqualTo("STALE_BLOCKED_BY_BARRIER");
        assertThat(esDocSearchable("501")).isFalse();

        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                501L, 6L, "Same Rev Public", "Same", "Same",
                3L, "Cloud", List.of("Search"), 10L, 1L, "2026-10-12T00:00:00Z"));
        HttpResponse<String> syncSameRevHidden = send("POST", "/api/admin/content/search/sync/501", null, Map.of("token", adminToken));
        assertThat(syncSameRevHidden.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(syncSameRevHidden.body()).path("data").path("action").asText()).isEqualTo("STALE_BLOCKED_BY_BARRIER");
        assertThat(esDocSearchable("501")).isFalse();

        // 6. MISSING on already-public article 502 -> tombstones once, then NOOP_MISSING
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                502L, 3L, "Will Be Missing", "Sum", "Body",
                3L, "Cloud", List.of(), 5L, 1L, "2026-10-12T00:00:00Z"));
        assertThat(send("POST", "/api/admin/content/search/sync/502", null, Map.of("token", adminToken)).statusCode()).isEqualTo(200);
        assertThat(esDocSearchable("502")).isTrue();

        contentStub.removeCurrentArticle(502L);
        HttpResponse<String> missing1 = send("POST", "/api/admin/content/search/sync/502", null, Map.of("token", adminToken));
        assertThat(missing1.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(missing1.body()).path("data").path("action").asText()).isEqualTo("TOMBSTONE_SYNCED");
        assertThat(esDocSearchable("502")).isFalse();

        HttpResponse<String> missing2 = send("POST", "/api/admin/content/search/sync/502", null, Map.of("token", adminToken));
        assertThat(missing2.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(missing2.body()).path("data").path("action").asText()).isEqualTo("NOOP_MISSING");

        // 7. Upstream 03 temporary 503 failure must return HTTP 503 code 517 AND preserve existing ES doc & projection!
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                503L, 4L, "Preserved During Outage", "Sum", "Body",
                3L, "Cloud", List.of(), 20L, 1L, "2026-10-12T00:00:00Z"));
        assertThat(send("POST", "/api/admin/content/search/sync/503", null, Map.of("token", adminToken)).statusCode()).isEqualTo(200);
        assertThat(esDocSearchable("503")).isTrue();

        contentStub.setInterceptor((req, def) ->
                LoopbackContentSnapshotStub.HttpStubResponse.status(503, "{\"error\":{\"code\":\"UNAVAILABLE\"}}"));
        try {
            HttpResponse<String> outageResp = send("POST", "/api/admin/content/search/sync/503", null, Map.of("token", adminToken));
            assertThat(outageResp.statusCode()).isEqualTo(503);
            assertThat(MAPPER.readTree(outageResp.body()).path("code").asInt()).isEqualTo(517);
            // Existing ES document and local projection MUST remain intact (never deleted or overwritten!)
            assertThat(esDocSearchable("503")).isTrue();
            ArticleProjectionRecord proj503 = consumerRepository.findProjection(1L, 503L).orElseThrow();
            assertThat(proj503.visibility()).isEqualTo(ArticleVisibility.PUBLIC);
            assertThat(proj503.title()).isEqualTo("Preserved During Outage");
        } finally {
            contentStub.clearInterceptor();
        }
    }

    @Test
    void internalOpsHttpConcurrentRetriesEnforceStrictIdempotencyAndSingleExecution() throws Exception {
        long now = System.currentTimeMillis();
        String evtB1 = UUID.randomUUID().toString();
        String evtB2 = UUID.randomUUID().toString();
        String evtS1 = UUID.randomUUID().toString();
        insertInbox(evtB1, "801", "APPLIED", now - 6000);
        insertTask(801L, "DEAD", 5, now - 6000, null, "NONE", "ERR_B1", now - 6000);
        insertInbox(evtB2, "802", "APPLIED", now - 5000);
        insertTask(802L, "DEAD", 5, now - 5000, null, "NONE", "ERR_B2", now - 5000);
        insertInbox(evtS1, "803", "APPLIED", now - 4000);
        insertTask(803L, "DEAD", 5, now - 4000, null, "NONE", "ERR_S1", now - 4000);

        String opsToken = serviceJwtSigner.signServiceToken(
                "knode-content", "knode-search", List.of("ops:read", "ops:retry"), 120);
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + opsToken,
                "Content-Type", "application/json"
        );

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // 1. Concurrent HTTP batch retry (limit=1, same replayRequestId)
            CountDownLatch batchStart = new CountDownLatch(1);
            String batchBody = "{\"replayRequestId\":\"http-conc-batch-1\",\"operator\":\"ops-http\",\"limit\":1}";
            Future<HttpResponse<String>> bf1 = pool.submit(() -> {
                batchStart.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/retry", batchBody, headers);
            });
            Future<HttpResponse<String>> bf2 = pool.submit(() -> {
                batchStart.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/retry", batchBody, headers);
            });
            batchStart.countDown();

            HttpResponse<String> br1 = bf1.get(15, TimeUnit.SECONDS);
            HttpResponse<String> br2 = bf2.get(15, TimeUnit.SECONDS);
            assertThat(br1.statusCode()).isEqualTo(200);
            assertThat(br2.statusCode()).isEqualTo(200);
            assertThat(br2.body()).isEqualTo(br1.body());
            assertThat(MAPPER.readTree(br1.body()).path("requeued").asText()).isEqualTo("1");

            SearchIndexTaskRecord t801 = consumerRepository.findIndexTask(1L, 801L).orElseThrow();
            SearchIndexTaskRecord t802 = consumerRepository.findIndexTask(1L, 802L).orElseThrow();
            assertThat(t801.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(t801.claimGeneration()).isEqualTo(2L);
            assertThat(t802.status()).isEqualTo(SearchIndexTaskStatus.DEAD);
            assertThat(t802.claimGeneration()).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = 'http-conc-batch-1'",
                    Long.class)).isEqualTo(1L);

            // Conflicting HTTP repeat with limit=2 -> 409 and 802 remains DEAD
            HttpResponse<String> batchConflict = send("POST", "/internal/v1/ops/consumers/retry",
                    "{\"replayRequestId\":\"http-conc-batch-1\",\"operator\":\"ops-http\",\"limit\":2}", headers);
            assertThat(batchConflict.statusCode()).isEqualTo(409);
            assertThat(consumerRepository.findIndexTask(1L, 802L).orElseThrow().status())
                    .isEqualTo(SearchIndexTaskStatus.DEAD);
            assertThat(consumerRepository.findIndexTask(1L, 802L).orElseThrow().claimGeneration()).isEqualTo(1L);

            // 2. Concurrent HTTP single retry (same eventId + replayRequestId)
            CountDownLatch singleStart = new CountDownLatch(1);
            String singleBody = "{\"replayRequestId\":\"http-conc-single-1\",\"operator\":\"ops-http\"}";
            Future<HttpResponse<String>> sf1 = pool.submit(() -> {
                singleStart.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/" + evtS1 + "/retry", singleBody, headers);
            });
            Future<HttpResponse<String>> sf2 = pool.submit(() -> {
                singleStart.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/" + evtS1 + "/retry", singleBody, headers);
            });
            singleStart.countDown();

            HttpResponse<String> sr1 = sf1.get(15, TimeUnit.SECONDS);
            HttpResponse<String> sr2 = sf2.get(15, TimeUnit.SECONDS);
            assertThat(sr1.statusCode()).isEqualTo(200);
            assertThat(sr2.statusCode()).isEqualTo(200);
            assertThat(sr2.body()).isEqualTo(sr1.body());
            assertThat(MAPPER.readTree(sr1.body()).path("requeued").asText()).isEqualTo("1");

            SearchIndexTaskRecord t803 = consumerRepository.findIndexTask(1L, 803L).orElseThrow();
            assertThat(t803.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(t803.claimGeneration()).isEqualTo(2L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = 'http-conc-single-1'",
                    Long.class)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void internalOpsHttpDistinguishesCaseVariantReplayRequestIdsAndReplaysExactResponses() throws Exception {
        long now = System.currentTimeMillis();
        insertTask(811L, "DEAD", 5, now - 5000, null, "NONE", "ERR_CASE_1", now - 5000);
        insertTask(812L, "DEAD", 5, now - 4000, null, "NONE", "ERR_CASE_2", now - 4000);

        String retryToken = serviceJwtSigner.signServiceToken(
                "knode-content", "knode-search", List.of("ops:read", "ops:retry"), 120);
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + retryToken,
                "Content-Type", "application/json"
        );

        HttpResponse<String> upper = send("POST", "/internal/v1/ops/consumers/retry",
                "{\"replayRequestId\":\"ReviewCaseKey\",\"operator\":\"ops-http\",\"limit\":1}", headers);
        HttpResponse<String> lower = send("POST", "/internal/v1/ops/consumers/retry",
                "{\"replayRequestId\":\"reviewcasekey\",\"operator\":\"ops-http\",\"limit\":1}", headers);

        assertThat(upper.statusCode()).isEqualTo(200);
        assertThat(lower.statusCode()).isEqualTo(200);
        JsonNode upperJson = MAPPER.readTree(upper.body());
        JsonNode lowerJson = MAPPER.readTree(lower.body());
        assertThat(upperJson.path("replayRequestId").asText()).isEqualTo("ReviewCaseKey");
        assertThat(upperJson.path("requeued").asText()).isEqualTo("1");
        assertThat(lowerJson.path("replayRequestId").asText()).isEqualTo("reviewcasekey");
        assertThat(lowerJson.path("requeued").asText()).isEqualTo("1");

        SearchIndexTaskRecord t811 = consumerRepository.findIndexTask(1L, 811L).orElseThrow();
        SearchIndexTaskRecord t812 = consumerRepository.findIndexTask(1L, 812L).orElseThrow();
        assertThat(t811.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(t811.claimGeneration()).isEqualTo(2L);
        assertThat(t812.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(t812.claimGeneration()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_ops_replay_audit", Long.class)).isEqualTo(2L);

        // Replay both IDs via HTTP -> exact committed response for each, zero extra mutations
        HttpResponse<String> upperReplay = send("POST", "/internal/v1/ops/consumers/retry",
                "{\"replayRequestId\":\"ReviewCaseKey\",\"operator\":\"ops-http\",\"limit\":1}", headers);
        HttpResponse<String> lowerReplay = send("POST", "/internal/v1/ops/consumers/retry",
                "{\"replayRequestId\":\"reviewcasekey\",\"operator\":\"ops-http\",\"limit\":1}", headers);
        assertThat(upperReplay.body()).isEqualTo(upper.body());
        assertThat(lowerReplay.body()).isEqualTo(lower.body());
        assertThat(consumerRepository.findIndexTask(1L, 811L).orElseThrow().claimGeneration()).isEqualTo(2L);
        assertThat(consumerRepository.findIndexTask(1L, 812L).orElseThrow().claimGeneration()).isEqualTo(2L);
    }

    private boolean esDocSearchable(String articleId) throws Exception {
        StatefulElasticsearchStub.StoredDoc doc = esStub.getWrittenDocument(ES_ALIAS + "_g1", articleId);
        assertThat(doc).as("ES doc %s", articleId).isNotNull();
        return MAPPER.readTree(doc.sourceJson()).path("searchable").asBoolean();
    }

    private void storeSession(String userId, String sid, String scope, String status) {
        Session session = new Session(1, sid, userId, scope, status, Instant.now().plusSeconds(3600).getEpochSecond());
        redisStub.putRaw("knode:" + ENV + ":v1:auth:session:" + sid, SessionCodec.encode(session));
    }

    private void insertInbox(String eventId, String articleId, String status, long processedAtMs) {
        jdbcTemplate.update("""
                INSERT INTO search_event_inbox (
                    event_id, event_type, schema_version, aggregate_type, aggregate_id, source_revision,
                    stream_record_id, status, received_at_ms, processed_at_ms
                ) VALUES (?, 'ArticlePublished', 1, 'article', ?, 1, '1-0', ?, ?, ?)
                """, eventId, articleId, status, processedAtMs, processedAtMs);
    }

    private void insertTask(
            long articleId,
            String status,
            int attempts,
            long dueAtMs,
            Long leaseUntilMs,
            String cacheStatus,
            String lastError,
            long updatedAtMs) {
        jdbcTemplate.update("""
                INSERT INTO search_index_task (
                    index_generation, target_index, article_id, target_projection_revision, operation,
                    payload_json, status, attempts, max_attempts, due_at_ms, lease_until_ms,
                    claim_generation, last_error, es_applied_revision,
                    cache_invalidation_status, cache_invalidation_attempts, cache_invalidation_due_at_ms,
                    updated_at_ms
                ) VALUES (1, ?, ?, 1, 'UPSERT', ?, ?, ?, 5, ?, ?, 1, ?, 0, ?, 0, ?, ?)
                """,
                ES_ALIAS + "_g1",
                articleId,
                "{\"id\":" + articleId + ",\"searchable\":true}",
                status,
                attempts,
                dueAtMs,
                leaseUntilMs,
                lastError,
                cacheStatus,
                "PENDING".equals(cacheStatus) ? updatedAtMs + 60_000 : null,
                updatedAtMs);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("HEAD".equals(method)) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            builder.method(method, publisher);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class TestTokenConfig {
        @Bean
        @Primary
        ServiceTokenClient testServiceTokenClient() {
            return new ServiceTokenClient() {
                @Override
                public String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
                    return "search04-content-token";
                }

                @Override
                public void invalidate(String audience, List<String> permissions) {
                }
            };
        }
    }
}
