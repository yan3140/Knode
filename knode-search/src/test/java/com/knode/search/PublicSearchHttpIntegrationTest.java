package com.knode.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.client.ServiceTokenClient;
import com.knode.search.support.LoopbackContentVisibilityStub;
import com.knode.search.support.LoopbackElasticsearchStub;
import com.knode.search.support.LoopbackRedisStub;
import com.knode.search.support.LoopbackUserTokenStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicSearchHttpIntegrationTest {
    private static final String TEST_INDEX = "knode_it_articles_search";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long SAMPLE_CREATE_TIME_MS = ZonedDateTime.of(
            2026, 11, 26, 14, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

    private static LoopbackElasticsearchStub esStub;
    private static LoopbackRedisStub redisStub;
    private static LoopbackUserTokenStub userStub;
    private static LoopbackContentVisibilityStub contentStub;
    private static final AtomicReference<List<LoopbackElasticsearchStub.RecordedRequest>> STARTUP_ES_REQUESTS =
            new AtomicReference<>();

    @LocalServerPort
    private int port;

    @Autowired
    private ServiceTokenClient serviceTokenClient;

    private HttpClient httpClient;

    @BeforeAll
    static void startStubs() throws Exception {
        esStub = new LoopbackElasticsearchStub();
        redisStub = new LoopbackRedisStub();
        userStub = new LoopbackUserTokenStub();
        contentStub = new LoopbackContentVisibilityStub();
    }

    @AfterAll
    static void stopStubs() throws Exception {
        if (contentStub != null) {
            contentStub.close();
        }
        if (userStub != null) {
            userStub.close();
        }
        if (redisStub != null) {
            redisStub.close();
        }
        if (esStub != null) {
            esStub.close();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("knode.environment", () -> "it-search");
        registry.add("knode.search.elasticsearch.url", () -> esStub.baseUrl());
        registry.add("knode.search.elasticsearch.index", () -> TEST_INDEX);
        registry.add("knode.search.elasticsearch.connect-timeout-ms", () -> "400");
        registry.add("knode.search.elasticsearch.connection-request-timeout-ms", () -> "400");
        registry.add("knode.search.elasticsearch.response-timeout-ms", () -> "2000");
        registry.add("knode.search.query.total-timeout-ms", () -> "2500");
        registry.add("knode.search.cache.enabled", () -> "true");
        registry.add("knode.search.cache.admission-threshold", () -> "2");
        registry.add("knode.search.cache.admission-ttl-seconds", () -> "60");
        registry.add("knode.search.cache.result-ttl-seconds", () -> "60");
        registry.add("knode.search.cache.empty-ttl-seconds", () -> "30");
        registry.add("knode.search.cache.jitter-seconds", () -> "0");
        registry.add("knode.search.cache.redis.host", () -> "127.0.0.1");
        registry.add("knode.search.cache.redis.port", () -> Integer.toString(redisStub.port()));
        registry.add("knode.search.cache.redis.connect-timeout-ms", () -> "400");
        registry.add("knode.search.cache.redis.command-timeout-ms", () -> "400");
        registry.add("knode.search.clients.user-origin", () -> userStub.baseUrl());
        registry.add("knode.search.clients.content-origin", () -> contentStub.baseUrl());
        registry.add("knode.search.clients.service-id", () -> "knode-search");
        registry.add("knode.search.clients.bootstrap-secret", () -> "it-bootstrap-secret");
        registry.add("knode.search.clients.connect-timeout-ms", () -> "400");
        registry.add("knode.search.clients.user-token-timeout-ms", () -> "500");
        registry.add("knode.search.clients.content-visibility-timeout-ms", () -> "1000");
    }

    @BeforeEach
    void resetState() {
        STARTUP_ES_REQUESTS.compareAndSet(null, esStub.requests());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .proxy(ProxySelector.of(null))
                .build();
        esStub.clearRequests();
        redisStub.reset();
        userStub.resetDefaultHandler();
        userStub.clearRequests();
        contentStub.reset();
        serviceTokenClient.invalidate("knode-content", List.of("content:visibility:read"));
    }

    @Test
    void supportsBothPublicPathsReadOnlyElasticsearchLongStringsAndGmt8Dates() throws Exception {
        // Verify Spring startup never touched or attempted to create an ES index.
        assertThat(STARTUP_ES_REQUESTS.get()).isEmpty();

        String bigId = "9007199254740993";
        String bigCategoryId = "9007199254740999";
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.ok(esResponse(
                1L,
                hitJson(bigId, "7", "Spring Cloud Guide", "Distributed search", bigCategoryId,
                        "Backend", List.of("Spring", "ES"), "https://cdn.example/1.png",
                        9007199254741001L, SAMPLE_CREATE_TIME_MS, 12.5,
                        Map.of("title", List.of("<em>Spring</em> Cloud Guide")))
        )));
        contentStub.setDecision(bigId, "7", "PUBLIC", "READABLE");

        HttpResponse<String> primary = get("/api/blog/search?keyword=Spring&pageNum=1&pageSize=5");
        HttpResponse<String> legacyAlias = get("/search?keyword=Spring&pageNum=1&pageSize=5");

        assertThat(primary.statusCode()).isEqualTo(200);
        assertThat(legacyAlias.statusCode()).isEqualTo(200);

        JsonNode body = OBJECT_MAPPER.readTree(primary.body());
        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(body.path("msg").asText()).isEqualTo("操作成功");
        assertThat(body.path("data").path("total").isTextual()).isTrue();
        assertThat(body.path("data").path("total").asText()).isEqualTo("1");

        JsonNode row = body.path("data").path("rows").get(0);
        assertThat(row.path("id").isTextual()).isTrue();
        assertThat(row.path("id").asText()).isEqualTo(bigId);
        assertThat(row.path("categoryId").asText()).isEqualTo(bigCategoryId);
        assertThat(row.path("viewCount").asText()).isEqualTo("9007199254741001");
        assertThat(row.path("createTime").asText()).isEqualTo("2026-11-26 14:30:00");
        assertThat(row.path("highlights").path("title").get(0).asText()).isEqualTo("<em>Spring</em> Cloud Guide");
        assertThat(row.has("sourceRevision")).isFalse();

        // Every ES call must be read-only POST /{index}/_search.
        assertThat(esStub.requests()).isNotEmpty();
        assertThat(esStub.requests()).allSatisfy(req -> {
            assertThat(req.method()).isEqualTo("POST");
            assertThat(req.path()).isEqualTo("/" + TEST_INDEX + "/_search");
        });
    }

    @Test
    void enforcesPaginationBoundariesAndParameterValidation() throws Exception {
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.ok(esResponse(0L)));

        // Default pageNum=1, pageSize=5
        HttpResponse<String> defaultResp = get("/api/blog/search?keyword=Java");
        assertThat(OBJECT_MAPPER.readTree(defaultResp.body()).path("code").asInt()).isEqualTo(200);
        JsonNode defaultEsBody = OBJECT_MAPPER.readTree(esStub.requests().get(0).body());
        assertThat(defaultEsBody.path("from").asInt()).isEqualTo(0);
        assertThat(defaultEsBody.path("size").asInt()).isEqualTo(5);

        // pageSize=99 clamps to 20
        esStub.clearRequests();
        HttpResponse<String> clampedResp = get("/api/blog/search?keyword=JavaClamp&pageNum=1&pageSize=99");
        assertThat(OBJECT_MAPPER.readTree(clampedResp.body()).path("code").asInt()).isEqualTo(200);
        JsonNode clampedEsBody = OBJECT_MAPPER.readTree(esStub.requests().get(0).body());
        assertThat(clampedEsBody.path("size").asInt()).isEqualTo(20);

        // pageNum=51 -> business code 515 without touching ES or Content
        esStub.clearRequests();
        contentStub.clearRequests();
        HttpResponse<String> page51 = get("/api/blog/search?keyword=Java&pageNum=51&pageSize=5");
        assertThat(page51.statusCode()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(page51.body()).path("code").asInt()).isEqualTo(515);
        assertThat(esStub.requests()).isEmpty();
        assertThat(contentStub.requests()).isEmpty();

        // Invalid sort, blank keyword, >40 chars keyword, non-numeric pageNum -> 515
        for (String path : List.of(
                "/api/blog/search?keyword=Java&sort=invalid",
                "/api/blog/search?keyword=%20%20",
                "/api/blog/search?keyword=" + "x".repeat(41),
                "/api/blog/search?keyword=Java&pageNum=abc")) {
            HttpResponse<String> invalid = get(path);
            assertThat(invalid.statusCode()).isEqualTo(200);
            assertThat(OBJECT_MAPPER.readTree(invalid.body()).path("code").asInt()).isEqualTo(515);
        }
    }

    @Test
    void sendsAllThreeSortOrdersAndHighlightConfigToElasticsearch() throws Exception {
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.ok(esResponse(0L)));

        HttpResponse<String> r1 = get("/api/blog/search?keyword=SortRel&sort=relevance");
        HttpResponse<String> r2 = get("/api/blog/search?keyword=SortLat&sort=latest");
        HttpResponse<String> r3 = get("/api/blog/search?keyword=SortHot&sort=hot");

        assertThat(OBJECT_MAPPER.readTree(r1.body()).path("code").asInt()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(r2.body()).path("code").asInt()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(r3.body()).path("code").asInt()).isEqualTo(200);
        assertThat(esStub.requests()).hasSize(3);

        JsonNode rel = OBJECT_MAPPER.readTree(esStub.requests().get(0).body());
        assertThat(rel.path("sort").get(0).has("_score")).isTrue();
        assertThat(rel.path("sort").get(1).has("createTime")).isTrue();
        assertThat(rel.path("highlight").path("pre_tags").get(0).asText()).isEqualTo("<em>");
        assertThat(rel.path("highlight").path("post_tags").get(0).asText()).isEqualTo("</em>");
        Set<String> highlightFieldNames = extractHighlightFieldNames(rel.path("highlight").path("fields"));
        assertThat(highlightFieldNames).containsExactlyInAnyOrder("title", "summary", "categoryName", "tagNames");
        assertThat(highlightFieldNames).doesNotContain("content");

        JsonNode latest = OBJECT_MAPPER.readTree(esStub.requests().get(1).body());
        assertThat(latest.path("sort").get(0).has("createTime")).isTrue();
        assertThat(latest.path("sort").get(1).has("_score")).isTrue();

        JsonNode hot = OBJECT_MAPPER.readTree(esStub.requests().get(2).body());
        assertThat(hot.path("sort").get(0).has("viewCount")).isTrue();
        assertThat(hot.path("sort").get(1).has("_score")).isTrue();
    }

    @Test
    void returnsNormalZeroHitWithoutCallingVisibilityBatch() throws Exception {
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.ok(esResponse(0L)));

        HttpResponse<String> resp = get("/api/blog/search?keyword=ZeroMatchKeyword");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode root = OBJECT_MAPPER.readTree(resp.body());
        assertThat(root.path("code").asInt()).isEqualTo(200);
        assertThat(root.path("data").path("rows")).isEmpty();
        assertThat(root.path("data").path("total").asText()).isEqualTo("0");
        assertThat(contentStub.requests()).isEmpty();
    }

    @Test
    void excludesV7CandidateWhenContentReturnsV8WithdrawnOrRevisionMismatchAndDoesNotCache() throws Exception {
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.ok(esResponse(
                3L,
                hitJson("101", "7", "Secret v7 Withdrawn Title", "Secret summary", "10",
                        "Tech", List.of("Java"), null, 50L, SAMPLE_CREATE_TIME_MS, 9.5,
                        Map.of("title", List.of("<em>Secret</em> v7 Withdrawn Title"))),
                hitJson("102", "4", "Stale v4 Revision Title", "Stale summary", "10",
                        "Tech", List.of("Java"), null, 20L, SAMPLE_CREATE_TIME_MS, 8.0,
                        Map.of("title", List.of("<em>Stale</em> v4"))),
                hitJson("103", "5", "Visible Spring Article", "Public summary", "10",
                        "Tech", List.of("Spring"), null, 88L, SAMPLE_CREATE_TIME_MS, 7.5,
                        Map.of("title", List.of("Visible <em>Spring</em> Article")))
        )));
        contentStub.setDecision("101", "8", "WITHDRAWN", "DENIED");
        contentStub.setDecision("102", "5", "PUBLIC", "READABLE");
        contentStub.setDecision("103", "5", "PUBLIC", "READABLE");

        // Repeat 3 times (> admission threshold 2) to prove pages with exclusions are NEVER cached.
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> resp = get("/api/blog/search?keyword=WithdrawnCheck&pageNum=1&pageSize=5");
            assertThat(resp.statusCode()).isEqualTo(200);
            String rawBody = resp.body();
            assertThat(rawBody).doesNotContain("Secret v7 Withdrawn Title");
            assertThat(rawBody).doesNotContain("<em>Secret</em>");
            assertThat(rawBody).doesNotContain("Stale v4 Revision Title");

            JsonNode root = OBJECT_MAPPER.readTree(rawBody);
            assertThat(root.path("code").asInt()).isEqualTo(200);
            assertThat(root.path("data").path("total").asText()).isEqualTo("1");
            assertThat(root.path("data").path("rows")).hasSize(1);
            assertThat(root.path("data").path("rows").get(0).path("id").asText()).isEqualTo("103");
        }

        assertThat(esStub.requests()).hasSize(3);
        assertThat(redisStub.snapshot().keySet())
                .noneMatch(key -> key.startsWith("knode:it-search:v1:search:result:"));
    }

    @Test
    void verifiesVisibilityOnRedisCacheHitAndEvictsStaleEntryWhenWithdrawn() throws Exception {
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.ok(esResponse(
                1L,
                hitJson("201", "7", "Cached v7 Article", "Cached summary", "11",
                        "Cloud", List.of("Redis"), null, 120L, SAMPLE_CREATE_TIME_MS, 10.0,
                        Map.of("title", List.of("<em>Cached</em> v7 Article")))
        )));
        contentStub.setDecision("201", "7", "PUBLIC", "READABLE");

        // 1st request: ES miss, admission count=1 -> not cached yet.
        HttpResponse<String> r1 = get("/api/blog/search?keyword=CacheVerify&pageNum=1&pageSize=5");
        assertThat(OBJECT_MAPPER.readTree(r1.body()).path("data").path("rows")).hasSize(1);
        assertThat(esStub.requests()).hasSize(1);
        assertThat(redisStub.snapshot().keySet())
                .noneMatch(k -> k.startsWith("knode:it-search:v1:search:result:"));

        // 2nd request: ES miss, admission count=2 -> cached under isolated keyspace.
        HttpResponse<String> r2 = get("/api/blog/search?keyword=CacheVerify&pageNum=1&pageSize=5");
        assertThat(OBJECT_MAPPER.readTree(r2.body()).path("data").path("rows")).hasSize(1);
        assertThat(esStub.requests()).hasSize(2);
        String resultKey = redisStub.snapshot().keySet().stream()
                .filter(k -> k.startsWith("knode:it-search:v1:search:result:"))
                .findFirst()
                .orElseThrow();

        // 3rd request: Redis hit (ES NOT called again), but Content visibility:batch IS called.
        HttpResponse<String> r3 = get("/api/blog/search?keyword=CacheVerify&pageNum=1&pageSize=5");
        assertThat(OBJECT_MAPPER.readTree(r3.body()).path("data").path("rows")).hasSize(1);
        assertThat(esStub.requests()).hasSize(2);
        assertThat(contentStub.requests()).hasSize(3);

        // Article 201 is now withdrawn at v8 while Redis still holds v7.
        contentStub.setDecision("201", "8", "WITHDRAWN", "DENIED");

        // 4th request: Redis hit -> 03 denies -> old v7 title/highlight NOT leaked -> cache key evicted.
        HttpResponse<String> r4 = get("/api/blog/search?keyword=CacheVerify&pageNum=1&pageSize=5");
        assertThat(r4.statusCode()).isEqualTo(200);
        assertThat(r4.body()).doesNotContain("Cached v7 Article");
        assertThat(r4.body()).doesNotContain("<em>Cached</em>");
        JsonNode root4 = OBJECT_MAPPER.readTree(r4.body());
        assertThat(root4.path("code").asInt()).isEqualTo(200);
        assertThat(root4.path("data").path("rows")).isEmpty();
        assertThat(root4.path("data").path("total").asText()).isEqualTo("0");
        assertThat(esStub.requests()).hasSize(2);
        assertThat(contentStub.requests()).hasSize(4);
        assertThat(redisStub.containsKey(resultKey)).isFalse();
    }

    @Test
    void isolatesRedisAdmissionReadWriteAndCorruptPayloadFailures() throws Exception {
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.ok(esResponse(
                1L,
                hitJson("301", "2", "Resilient Article", "Summary", "12",
                        "Ops", List.of("Cache"), null, 15L, SAMPLE_CREATE_TIME_MS, 5.0,
                        Map.of("title", List.of("<em>Resilient</em> Article")))
        )));
        contentStub.setDecision("301", "2", "PUBLIC", "READABLE");

        // 1) Redis INCR admission failure preserves successful ES+03 result.
        redisStub.failCommand("INCR", "ERR admission unavailable");
        HttpResponse<String> admitFail = get("/api/blog/search?keyword=ResilientAdmit&pageNum=1&pageSize=5");
        assertThat(OBJECT_MAPPER.readTree(admitFail.body()).path("code").asInt()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(admitFail.body()).path("data").path("rows")).hasSize(1);

        // 2) Redis GET failure falls back to ES.
        redisStub.clearFaults();
        redisStub.failCommand("GET", "ERR redis read down");
        HttpResponse<String> readFail = get("/api/blog/search?keyword=ResilientRead&pageNum=1&pageSize=5");
        assertThat(OBJECT_MAPPER.readTree(readFail.body()).path("code").asInt()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(readFail.body()).path("data").path("rows")).hasSize(1);

        // 3) Redis write (SET/PSETEX/SETEX) failure preserves successful ES+03 result.
        redisStub.clearFaults();
        redisStub.failCommand("WRITE", "ERR redis read-only");
        get("/api/blog/search?keyword=ResilientWrite&pageNum=1&pageSize=5");
        HttpResponse<String> writeFail = get("/api/blog/search?keyword=ResilientWrite&pageNum=1&pageSize=5");
        assertThat(OBJECT_MAPPER.readTree(writeFail.body()).path("code").asInt()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(writeFail.body()).path("data").path("rows")).hasSize(1);

        // 4) Corrupt cached payload falls back to ES.
        redisStub.reset();
        // Warm cache first so we know exact result key, then corrupt it.
        get("/api/blog/search?keyword=ResilientCorrupt&pageNum=1&pageSize=5");
        get("/api/blog/search?keyword=ResilientCorrupt&pageNum=1&pageSize=5");
        String resultKey = redisStub.snapshot().keySet().stream()
                .filter(k -> k.startsWith("knode:it-search:v1:search:result:"))
                .findFirst()
                .orElseThrow();
        redisStub.putRaw(resultKey, "{corrupt-json-payload");
        int esBefore = esStub.requests().size();
        HttpResponse<String> corruptFallback = get("/api/blog/search?keyword=ResilientCorrupt&pageNum=1&pageSize=5");
        assertThat(OBJECT_MAPPER.readTree(corruptFallback.body()).path("code").asInt()).isEqualTo(200);
        assertThat(OBJECT_MAPPER.readTree(corruptFallback.body()).path("data").path("rows")).hasSize(1);
        assertThat(esStub.requests().size()).isEqualTo(esBefore + 1);
    }

    @Test
    void mapsEsContentUserAndTotalTimeoutBudgetFailuresToCode517() throws Exception {
        String singleHit = esResponse(
                1L,
                hitJson("401", "3", "Fail Closed Article", "Summary", "13",
                        "Sec", List.of("Auth"), null, 9L, SAMPLE_CREATE_TIME_MS, 6.0,
                        Map.of("title", List.of("<em>Fail</em> Closed")))
        );

        // 1) Elasticsearch 500 without cache -> 517
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.status(500, "{\"error\":\"es_down\"}"));
        assertCode517(get("/api/blog/search?keyword=EsDownCase"));

        // 2) Content visibility:batch timeout (> 1000ms) -> 517 (never leaks ES hits)
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.ok(singleHit));
        contentStub.setOverrideHandler(req -> LoopbackContentVisibilityStub.StubResponse.delayedOk(
                "{\"items\":[{\"articleId\":\"401\",\"sourceRevision\":\"3\",\"visibility\":\"PUBLIC\",\"decision\":\"READABLE\"}]}",
                1250L));
        HttpResponse<String> contentTimeout = get("/api/blog/search?keyword=ContentTimeoutCase");
        assertCode517(contentTimeout);
        assertThat(contentTimeout.body()).doesNotContain("Fail Closed Article");

        // 3) Content visibility:batch missing item -> 517
        contentStub.setOverrideHandler(req -> LoopbackContentVisibilityStub.StubResponse.ok("{\"items\":[]}"));
        assertCode517(get("/api/blog/search?keyword=ContentMissingItemCase"));

        // 4) User service-tokens 401 -> 517 (HTTP 200, never leaks 401 to browser)
        contentStub.clearOverrideHandler();
        contentStub.setDecision("401", "3", "PUBLIC", "READABLE");
        serviceTokenClient.invalidate("knode-content", List.of("content:visibility:read"));
        userStub.setHandler(req -> LoopbackUserTokenStub.StubResponse.status(401, "{\"error\":\"INVALID_BOOTSTRAP\"}"));
        HttpResponse<String> authFailure = get("/api/blog/search?keyword=UserAuthFailureCase");
        assertCode517(authFailure);

        // 5) Shared total timeout budget exhaustion (ES 1800ms < 2000ms, Content 850ms < 1000ms, sum > 2500ms) -> 517
        userStub.resetDefaultHandler();
        serviceTokenClient.invalidate("knode-content", List.of("content:visibility:read"));
        esStub.setHandler(req -> LoopbackElasticsearchStub.StubResponse.delayedOk(singleHit, 1800L));
        contentStub.setOverrideHandler(req -> LoopbackContentVisibilityStub.StubResponse.delayedOk(
                "{\"items\":[{\"articleId\":\"401\",\"sourceRevision\":\"3\",\"visibility\":\"PUBLIC\",\"decision\":\"READABLE\"}]}",
                850L));
        assertCode517(get("/api/blog/search?keyword=TotalBudgetExhausted"));
    }

    private static Set<String> extractHighlightFieldNames(JsonNode fieldsNode) {
        Set<String> names = new LinkedHashSet<>();
        if (fieldsNode.isObject()) {
            fieldsNode.fieldNames().forEachRemaining(names::add);
        } else if (fieldsNode.isArray()) {
            for (JsonNode entry : fieldsNode) {
                if (entry.isObject()) {
                    entry.fieldNames().forEachRemaining(names::add);
                }
            }
        }
        return names;
    }

    private void assertCode517(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        assertThat(root.path("code").asInt()).isEqualTo(517);
        assertThat(root.path("msg").asText()).isEqualTo("搜索服务暂不可用");
        assertThat(root.has("data")).isFalse();
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        String encoded = pathAndQuery;
        if (pathAndQuery.contains(" ")) {
            encoded = pathAndQuery.replace(" ", URLEncoder.encode(" ", StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + encoded))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("X-Request-Id", "it-req-001")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String esResponse(long total, String... hits) {
        return "{\"took\":2,\"timed_out\":false,\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0},"
                + "\"hits\":{\"total\":{\"value\":" + total
                + ",\"relation\":\"eq\"},\"hits\":[" + String.join(",", hits) + "]}}";
    }

    private static String hitJson(
            String id,
            String sourceRevision,
            String title,
            String summary,
            String categoryId,
            String categoryName,
            List<String> tagNames,
            String thumbnail,
            long viewCount,
            long createTimeEpochMillis,
            double score,
            Map<String, List<String>> highlights) {
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("id", id);
        source.put("sourceRevision", sourceRevision);
        source.put("title", title);
        source.put("summary", summary);
        source.put("categoryId", categoryId);
        source.put("categoryName", categoryName);
        source.put("tagNames", tagNames);
        source.put("thumbnail", thumbnail);
        source.put("viewCount", viewCount);
        source.put("createTime", createTimeEpochMillis);

        Map<String, Object> hit = new java.util.LinkedHashMap<>();
        hit.put("_index", TEST_INDEX);
        hit.put("_id", id);
        hit.put("_score", score);
        hit.put("_source", source);
        hit.put("highlight", highlights);
        try {
            return OBJECT_MAPPER.writeValueAsString(hit);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build ES hit JSON", ex);
        }
    }
}
