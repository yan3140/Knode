package com.knode.search.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.search.KnodeSearchApplication;
import com.knode.search.client.ServiceTokenClient;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexDocument;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.consumer.stream.ContentStreamConsumer;
import com.knode.search.model.QueryBudget;
import com.knode.search.rebuild.es.SearchIndexAdminClient;
import com.knode.search.rebuild.persistence.RebuildJobRecord;
import com.knode.search.rebuild.persistence.RebuildPhase;
import com.knode.search.rebuild.service.SearchRebuildService;
import com.knode.search.support.LoopbackContentSnapshotStub;
import com.knode.search.support.LoopbackUserAdminAuthStub;
import com.knode.search.support.TestJwtSigner;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Standalone live-middleware verification runner for SEARCH-04 / SEARCH-03-R6 / SEARCH-03-R7.
 * Connects to real Redis and real Elasticsearch via environment variables using
 * strictly isolated KNODE_ENVIRONMENT, Redis key prefix, Stream key, ES test alias
 * and physical index, synthetic data, isolated H2 database, and loopback stubs.
 * Always cleans up its own isolated Redis keys and ES test indices/aliases in finally.
 */
public final class RealMiddlewareVerificationRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RealMiddlewareVerificationRunner() {
    }

    public static void main(String[] args) throws Exception {
        String redisHost = requireEnv("IT_REDIS_HOST");
        int redisPort = Integer.parseInt(envOrDefault("IT_REDIS_PORT", "6379"));
        int redisDb = Integer.parseInt(envOrDefault("IT_REDIS_DB", "0"));
        String redisPassword = System.getenv("IT_REDIS_PASSWORD");
        String esUrl = requireEnv("IT_ES_URL").replaceAll("/+$", "");

        String runSuffix = Long.toString(System.currentTimeMillis());
        String envPrefix = envOrDefault("IT_ENV_PREFIX", "it-search04");
        String esPrefix = envOrDefault("IT_ES_PREFIX", "knode_it_search04_");
        String envName = envPrefix + "-" + runSuffix;
        String keyPrefix = "knode:" + envName + ":";
        String streamKey = keyPrefix + "v1:events:content";
        String consumerGroup = "knode-search-v1";
        String esAlias = esPrefix + runSuffix + "_articles";
        String esPhysicalG1 = esAlias + "_g1";
        String esPhysicalG2 = esAlias + "_g2";
        String dbName = "search_live_it_" + runSuffix;

        TestJwtSigner userSigner = TestJwtSigner.generate("knode-it-user-k1");
        TestJwtSigner serviceSigner = TestJwtSigner.generate("knode-it-svc-k1");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        RedisClient redisClient = createRedisClient(redisHost, redisPort, redisDb, redisPassword);

        LoopbackContentSnapshotStub contentStub = new LoopbackContentSnapshotStub();
        contentStub.setExpectedBearerToken("it-content-token");
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                900101L, 2L, "星云分布式检索联调测试文章", "真实中间件链路验证摘要",
                "Spring Cloud 检索服务通过真实 Redis Stream 消费并写入 Elasticsearch 9.4.3",
                10L, "架构", List.of("SpringCloud", "Elasticsearch"), 88L, 2L, "2026-11-26T15:00:00Z"));
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                900102L, 3L, "故障恢复运维重试验证文章 OpsDeadRetry900102", "死信任务经内部运维重试恢复",
                "真实 Redis 与 Elasticsearch 联调恢复验证",
                10L, "运维", List.of("Ops", "Retry"), 15L, 1L, "2026-11-26T15:30:00Z"));
        LoopbackUserAdminAuthStub userAuthStub = new LoopbackUserAdminAuthStub();
        userAuthStub.setAuthority("1", List.of("admin"), List.of("*:*:*"));

        ServletWebServerApplicationContext ctx = null;
        try (StatefulRedisConnection<String, String> redisConn = redisClient.connect()) {
            RedisCommands<String, String> redis = redisConn.sync();
            String pong = redis.ping();
            require("PONG".equalsIgnoreCase(pong), "Redis PING failed: " + pong);
            System.out.println("[LIVE-IT] Redis connected host=" + redisHost + ":" + redisPort + " db=" + redisDb + " ping=" + pong);

            HttpResponse<String> esRoot = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(esRoot.statusCode() == 200, "ES root check failed: " + esRoot.statusCode());
            JsonNode esInfo = MAPPER.readTree(esRoot.body());
            System.out.println("[LIVE-IT] ES connected url=" + esUrl
                    + " cluster=" + esInfo.path("cluster_name").asText()
                    + " version=" + esInfo.path("version").path("number").asText());

            // Pre-create isolated Redis Stream consumer group on isolated stream key ONLY
            redis.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0-0"),
                    consumerGroup, XGroupCreateArgs.Builder.mkstream(true));
            System.out.println("[LIVE-IT] Created isolated stream=" + streamKey + " group=" + consumerGroup);

            // Boot real KnodeSearchApplication pointing to REAL Redis & REAL ES + isolated H2
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("server.port", "0");
            props.put("knode.environment", envName);
            props.put("knode.search.elasticsearch.url", esUrl);
            props.put("knode.search.elasticsearch.index", esAlias);
            props.put("knode.search.clients.content-origin", contentStub.baseUrl());
            props.put("knode.search.clients.user-origin", userAuthStub.baseUrl());
            props.put("knode.search.clients.content-snapshot-timeout-ms", "5000");

            props.put("knode.search.cache.enabled", "true");
            props.put("knode.search.cache.redis.host", redisHost);
            props.put("knode.search.cache.redis.port", Integer.toString(redisPort));
            props.put("knode.search.cache.redis.database", Integer.toString(redisDb));
            props.put("knode.search.cache.redis.password", redisPassword == null ? "" : redisPassword);

            props.put("knode.search.consumer.enabled", "true");
            props.put("knode.search.consumer.datasource.url",
                    "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
            props.put("knode.search.consumer.datasource.driver-class-name", "org.h2.Driver");
            props.put("knode.search.consumer.datasource.username", "sa");
            props.put("knode.search.consumer.datasource.password", "");

            props.put("knode.search.consumer.stream.enabled", "true");
            props.put("knode.search.consumer.stream.worker-enabled", "false");
            props.put("knode.search.consumer.stream.redis.host", redisHost);
            props.put("knode.search.consumer.stream.redis.port", Integer.toString(redisPort));
            props.put("knode.search.consumer.stream.redis.database", Integer.toString(redisDb));
            props.put("knode.search.consumer.stream.redis.password", redisPassword == null ? "" : redisPassword);
            props.put("knode.search.consumer.stream.stream-key", streamKey);
            props.put("knode.search.consumer.stream.group-name", consumerGroup);
            props.put("knode.search.consumer.stream.block-timeout-ms", "200");

            props.put("knode.search.consumer.indexer.enabled", "true");
            props.put("knode.search.consumer.indexer.worker-enabled", "false");

            props.put("knode.search.rebuild.enabled", "true");
            props.put("knode.search.rebuild.worker-enabled", "false");
            props.put("knode.search.rebuild.instance-id", "live-it-inst-1");

            props.put("knode.search.auth.expected-service-audience", "knode-search");
            props.put("knode.search.auth.trusted-public-keys.knode-it-user-k1", userSigner.publicKeyPem());
            props.put("knode.search.auth.service-trusted-public-keys.knode-it-svc-k1", serviceSigner.publicKeyPem());
            props.put("knode.search.auth.session.redis.enabled", "true");
            props.put("knode.search.auth.session.redis.host", redisHost);
            props.put("knode.search.auth.session.redis.port", Integer.toString(redisPort));
            props.put("knode.search.auth.session.redis.database", Integer.toString(redisDb));
            props.put("knode.search.auth.session.redis.password", redisPassword == null ? "" : redisPassword);

            SpringApplication app = new SpringApplication(KnodeSearchApplication.class, LiveTestTokenConfig.class);
            app.setAdditionalProfiles("real-middleware-it");
            app.addInitializers(ac -> ac.getEnvironment().getPropertySources().addFirst(new MapPropertySource("liveItProps", props)));
            ctx = (ServletWebServerApplicationContext) app.run();
            int localPort = ctx.getWebServer().getPort();
            String baseUrl = "http://127.0.0.1:" + localPort;

            // Initialize isolated H2 schema & generation=1 pointing to isolated ES physical index
            DataSource ds = ctx.getBean("searchConsumerDataSource", DataSource.class);
            new ResourceDatabasePopulator(new ClassPathResource("db/search-consumer-schema.sql")).execute(ds);
            JdbcTemplate jdbc = ctx.getBean("searchConsumerJdbcTemplate", JdbcTemplate.class);
            jdbc.update("""
                    INSERT INTO search_index_generation (
                        alias_name, active_generation, active_physical_index,
                        require_cold_visibility_guard, cutover_state,
                        active_cache_invalidation_status, active_cache_invalidation_attempts, updated_at_ms
                    ) VALUES (?, 1, ?, 0, 'NORMAL', 'DONE', 0, ?)
                    """, esAlias, esPhysicalG1, System.currentTimeMillis());

            // Create isolated ES physical index with smartcn analyzer & attach isolated alias
            SearchIndexAdminClient esAdmin = ctx.getBean(SearchIndexAdminClient.class);
            esAdmin.ensurePhysicalIndex(esPhysicalG1);
            esAdmin.switchAliasAtomically(esAlias, Set.of(), esPhysicalG1);
            System.out.println("[LIVE-IT] Created isolated ES physical index=" + esPhysicalG1 + " alias=" + esAlias);

            ContentStreamConsumer streamConsumer = ctx.getBean(ContentStreamConsumer.class);
            SearchIndexTaskWorker indexWorker = ctx.getBean(SearchIndexTaskWorker.class);
            SearchConsumerRepository consumerRepo = ctx.getBean(SearchConsumerRepository.class);
            MeterRegistry meterRegistry = ctx.getBean(MeterRegistry.class);
            String opsToken = serviceSigner.signServiceToken(
                    "knode-content", "knode-search", List.of("ops:read", "ops:retry"), 120);

            // =========================================================================
            // CHAIN 1: Real Redis Stream -> Consumer -> Indexer -> Real ES -> Public Search + Real Redis Cache
            // =========================================================================
            String evt1 = UUID.randomUUID().toString();
            String event1Json = """
                    {
                      "eventId": "%s",
                      "eventType": "ArticlePublished",
                      "schemaVersion": 1,
                      "aggregateType": "ARTICLE",
                      "aggregateId": "900101",
                      "sourceRevision": "2",
                      "publicationFactId": "pub-900101-v2",
                      "occurredAt": "2026-11-26T16:00:00Z",
                      "producer": "knode-content",
                      "visibility": "PUBLIC",
                      "payload": {
                        "articleId": "900101",
                        "authorId": "50",
                        "title": "星云分布式检索联调测试文章",
                        "summary": "真实中间件链路验证摘要",
                        "content": "Spring Cloud 检索服务通过真实 Redis Stream 消费并写入 Elasticsearch 9.4.3",
                        "thumbnail": null,
                        "isComment": "1",
                        "category": {"id": "10", "name": "架构"},
                        "tags": [{"id": "1", "name": "SpringCloud"}, {"id": "2", "name": "Elasticsearch"}],
                        "createTime": "2026-11-26T15:00:00Z",
                        "publishedAt": "2026-11-26T16:00:00Z",
                        "viewCount": "88",
                        "counterRevision": "2"
                      }
                    }
                    """.formatted(evt1);
            String streamRecordId = redis.xadd(streamKey, Map.of("event", event1Json));
            require(streamRecordId != null && !streamRecordId.isBlank(), "XADD failed");

            ContentStreamConsumer.PollSummary pollSummary = streamConsumer.pollOnce();
            require(pollSummary.freshCount() == 1 && pollSummary.ackedCount() == 1 && pollSummary.dbFailedCount() == 0,
                    "Unexpected pollSummary: " + pollSummary);

            SearchIndexTaskWorker.WorkerCycleSummary work1 = indexWorker.processOnce();
            require(work1.appliedCount() == 1 && work1.failedCount() == 0, "Unexpected work1: " + work1);
            esAdmin.refreshIndex(esPhysicalG1);

            // Public search #1 (hits real ES 9.4.3 smartcn + populates real Redis cache)
            String q1 = URLEncoder.encode("星云分布式检索", StandardCharsets.UTF_8);
            HttpResponse<String> search1 = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + q1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(search1.statusCode() == 200, "Public search #1 failed: " + search1.statusCode());
            JsonNode s1Json = MAPPER.readTree(search1.body());
            require(s1Json.path("code").asInt() == 200, "Public search #1 code != 200: " + search1.body());
            require("1".equals(s1Json.path("data").path("total").asText()), "Expected 1 hit on search #1: " + search1.body());
            require("900101".equals(s1Json.path("data").path("rows").get(0).path("id").asText()),
                    "Expected article 900101 on search #1");

            // Query #2 reaches 2-hit admission threshold and writes real Redis cache result key; Query #3 hits real Redis cache
            HttpResponse<String> search2 = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + q1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(search2.statusCode() == 200, "Public search #2 failed");

            List<String> cacheKeys = scanKeys(redis, keyPrefix + "v1:search:*");
            require(!cacheKeys.isEmpty(), "Expected real Redis cache keys under " + keyPrefix + "v1:search:*");

            double hitsBefore = counterValue(meterRegistry, "knode.search.cache", "outcome", "hit");
            HttpResponse<String> search3 = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + q1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(search3.statusCode() == 200, "Public search #3 failed");
            JsonNode s3Json = MAPPER.readTree(search3.body());
            require("1".equals(s3Json.path("data").path("total").asText())
                    && "900101".equals(s3Json.path("data").path("rows").get(0).path("id").asText()),
                    "Unexpected cached response on search #3: " + search3.body());
            double hitsAfter = counterValue(meterRegistry, "knode.search.cache", "outcome", "hit");
            require(hitsAfter > hitsBefore, "Expected real Redis cache hit on third identical query");
            System.out.println("[LIVE-IT] CHAIN 1 PASSED: streamRecordId=" + streamRecordId
                    + " -> consumer acked=1 -> ES indexed=900101 -> public search hit=1 -> real Redis cache keys="
                    + cacheKeys.size() + " cacheHitDelta=" + (hitsAfter - hitsBefore));

            // =========================================================================
            // CHAIN 2: Failed/DEAD work -> 05 SEARCH-OPS Retry -> Real ES Recovery -> Public Search Hit
            // =========================================================================
            String evtDead = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            jdbc.update("""
                    INSERT INTO search_event_inbox (
                        event_id, event_type, schema_version, aggregate_type, aggregate_id, source_revision,
                        stream_record_id, status, received_at_ms, processed_at_ms
                    ) VALUES (?, 'ArticlePublished', 1, 'ARTICLE', '900102', 3, '2-0', 'APPLIED', ?, ?)
                    """, evtDead, now - 5000, now - 5000);
            String doc900102 = MAPPER.writeValueAsString(SearchIndexDocument.upsert(
                    900102L,
                    3L,
                    "故障恢复运维重试验证文章 OpsDeadRetry900102",
                    "死信任务经内部运维重试恢复",
                    "真实 Redis 与 Elasticsearch 联调恢复验证",
                    10L,
                    "运维",
                    List.of("Ops", "Retry"),
                    null,
                    15L,
                    Instant.parse("2026-11-26T15:30:00Z").toEpochMilli()
            ));
            jdbc.update("""
                    INSERT INTO search_index_task (
                        index_generation, target_index, article_id, target_projection_revision, operation,
                        payload_json, status, attempts, max_attempts, due_at_ms, lease_until_ms,
                        claim_generation, last_error, es_applied_revision,
                        cache_invalidation_status, cache_invalidation_attempts, updated_at_ms
                    ) VALUES (1, ?, 900102, 1, 'UPSERT', ?, 'DEAD', 5, 5, ?, NULL, 1, 'SIMULATED_ES_OUTAGE', 0, 'NONE', 0, ?)
                    """, esPhysicalG1, doc900102, now - 5000, now - 5000);

            HttpResponse<String> opsSummaryResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/ops/consumers/summary"))
                            .header("Authorization", "Bearer " + opsToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(opsSummaryResp.statusCode() == 200, "ops summary failed: " + opsSummaryResp.statusCode());
            JsonNode opsSummary = MAPPER.readTree(opsSummaryResp.body());
            require("1".equals(opsSummary.path("indexTasks").path("DEAD").asText()),
                    "Expected DEAD=1 in ops summary: " + opsSummaryResp.body());
            require("AVAILABLE".equals(opsSummary.path("stream").path("status").asText()),
                    "Expected stream AVAILABLE: " + opsSummaryResp.body());
            require("0".equals(opsSummary.path("stream").path("pendingCount").asText()),
                    "Expected stream pendingCount=0: " + opsSummaryResp.body());

            HttpResponse<String> opsRetryResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/ops/consumers/" + evtDead + "/retry"))
                            .header("Authorization", "Bearer " + opsToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"replayRequestId\":\"live-it-retry-1\",\"operator\":\"live-it\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(opsRetryResp.statusCode() == 200, "ops single retry failed: " + opsRetryResp.statusCode());
            JsonNode opsRetryJson = MAPPER.readTree(opsRetryResp.body());
            require("ACCEPTED".equals(opsRetryJson.path("status").asText())
                            && "1".equals(opsRetryJson.path("requeued").asText()),
                    "Unexpected ops retry body: " + opsRetryResp.body());

            SearchIndexTaskWorker.WorkerCycleSummary work2 = indexWorker.processOnce();
            if (work2.appliedCount() != 1 || work2.failedCount() != 0) {
                String err = consumerRepo.findIndexTask(1L, 900102L).map(SearchIndexTaskRecord::lastError).orElse("none");
                require(false, "Unexpected work2: " + work2 + " lastError=" + err);
            }
            SearchIndexTaskRecord task900102 = consumerRepo.findIndexTask(1L, 900102L).orElseThrow();
            require(task900102.status() == SearchIndexTaskStatus.DONE, "Task 900102 not DONE");
            esAdmin.refreshIndex(esPhysicalG1);

            String q2 = URLEncoder.encode("OpsDeadRetry900102", StandardCharsets.UTF_8);
            HttpResponse<String> searchDeadRecovered = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + q2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(searchDeadRecovered.statusCode() == 200, "Recovered search failed");
            JsonNode sDeadJson = MAPPER.readTree(searchDeadRecovered.body());
            require("1".equals(sDeadJson.path("data").path("total").asText())
                            && "900102".equals(sDeadJson.path("data").path("rows").get(0).path("id").asText()),
                    "Expected recovered article 900102 hit: " + searchDeadRecovered.body());
            System.out.println("[LIVE-IT] CHAIN 2 PASSED: DEAD eventId=" + evtDead
                    + " -> SEARCH-OPS single retry ACCEPTED -> worker DONE -> ES indexed=900102 -> public search hit=1");

            // =========================================================================
            // CHAIN 3: Real Redis Auth Session + Admin /sync/900103 -> Real ES Hit
            // =========================================================================
            String adminSid = UUID.randomUUID().toString();
            String adminJwt = userSigner.signUserToken("1", adminSid, "admin", 300);
            Session session = new Session(1, adminSid, "1", "admin", "ACTIVE", Instant.now().plusSeconds(300).getEpochSecond());
            redis.setex(keyPrefix + "v1:auth:session:" + adminSid, 300, SessionCodec.encode(session));

            contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                    900103L, 4L, "后台单篇同步真实索引验证 AdminSingleSync900103", "管理员触发同步", "单篇同步经版本屏障写入真实 ES",
                    10L, "架构", List.of("AdminSync"), 33L, 1L, "2026-11-26T16:05:00Z"));
            HttpResponse<String> adminSyncResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/admin/content/search/sync/900103"))
                            .header("token", adminJwt)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(adminSyncResp.statusCode() == 200, "Admin sync failed: " + adminSyncResp.statusCode() + " body=" + adminSyncResp.body());
            JsonNode syncJson = MAPPER.readTree(adminSyncResp.body());
            require(syncJson.path("code").asInt() == 200
                            && "UPSERT_SYNCED".equals(syncJson.path("data").path("action").asText()),
                    "Unexpected admin sync body: " + adminSyncResp.body());
            esAdmin.refreshIndex(esPhysicalG1);

            String q3 = URLEncoder.encode("AdminSingleSync900103", StandardCharsets.UTF_8);
            HttpResponse<String> searchSync = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + q3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode sSyncJson = MAPPER.readTree(searchSync.body());
            require("1".equals(sSyncJson.path("data").path("total").asText())
                            && "900103".equals(sSyncJson.path("data").path("rows").get(0).path("id").asText()),
                    "Expected admin-synced article 900103 hit: " + searchSync.body());
            System.out.println("[LIVE-IT] CHAIN 3 PASSED: real Redis auth session -> admin POST /api/admin/content/search/sync/900103"
                    + " -> UPSERT_SYNCED -> ES indexed=900103 -> public search hit=1");

            // =========================================================================
            // CHAIN 4 (SEARCH-03-R6): Candidate g2 Snapshot (PUBLIC v3) + Incremental (DELETED v5)
            // BEFORE active g1 Stream -> active g1 STILL applies, indexes, hits v3 & hides deleted v5!
            // =========================================================================
            SearchRebuildService rebuildService = ctx.getBean(SearchRebuildService.class);
            contentStub.setLivePublicArticles(List.of(
                    LoopbackContentSnapshotStub.snapshotItem(
                            900101L, 3L, "星云候选快照先行更新 R6SnapshotFirstV3", "候选快照先行导入摘要",
                            "候选代 g2 先导入 v3 后活动代 g1 仍应正常消费并命中",
                            10L, "架构", List.of("SpringCloud", "R6"), 99L, 3L, "2026-11-27T08:00:00Z"),
                    LoopbackContentSnapshotStub.snapshotItem(
                            900103L, 4L, "后台单篇同步真实索引验证 AdminSingleSync900103", "管理员触发同步",
                            "单篇同步经版本屏障写入真实 ES",
                            10L, "架构", List.of("AdminSync"), 33L, 1L, "2026-11-26T16:05:00Z")
            ));

            RebuildJobRecord rbJob = rebuildService.startRebuild("live-it-r6-" + runSuffix);
            for (int step = 0; step < 12 && rbJob.phase() != RebuildPhase.INCREMENTAL_CATCHUP; step++) {
                rbJob = rebuildService.advanceJob(rbJob.jobId()).job();
            }
            require(rbJob.phase() == RebuildPhase.INCREMENTAL_CATCHUP && rbJob.candidateGeneration() == 2L,
                    "Expected candidate g2 in INCREMENTAL_CATCHUP, got: " + rbJob);

            String evtDel900103 = UUID.randomUUID().toString();
            String del900103Json = """
                    {
                      "eventId": "%s",
                      "eventType": "ArticleDeleted",
                      "schemaVersion": 2,
                      "aggregateType": "ARTICLE",
                      "aggregateId": "900103",
                      "sourceRevision": "5",
                      "publicationFactId": null,
                      "occurredAt": "2026-11-27T08:01:00Z",
                      "producer": "knode-content",
                      "visibility": "DELETED",
                      "payload": {
                        "articleId": "900103"
                      }
                    }
                    """.formatted(evtDel900103);
            contentStub.appendCommitBatch(List.of(MAPPER.readTree(del900103Json)));
            rbJob = rebuildService.advanceJob(rbJob.jobId()).job();
            require(esAdmin.getAliasTargets(esAlias).equals(Set.of(esPhysicalG1)),
                    "Alias must still point exclusively to g1 while candidate g2 is unactivated");
            require(consumerRepo.findVersionBarrier(900101L).orElseThrow().maxContentRevision() == 2L,
                    "Candidate g2 snapshot MUST NOT advance global barrier for 900101 past g1 v2");
            require(consumerRepo.findVersionBarrier(900103L).orElseThrow().maxContentRevision() == 4L,
                    "Candidate g2 incremental delete MUST NOT advance global barrier for 900103 past g1 v4");

            String evtUpd900101 = UUID.randomUUID().toString();
            String upd900101Json = """
                    {
                      "eventId": "%s",
                      "eventType": "ArticleContentChanged",
                      "schemaVersion": 2,
                      "aggregateType": "ARTICLE",
                      "aggregateId": "900101",
                      "sourceRevision": "3",
                      "publicationFactId": "pub-900101-v3",
                      "occurredAt": "2026-11-27T08:02:00Z",
                      "producer": "knode-content",
                      "visibility": "PUBLIC",
                      "payload": {
                        "articleId": "900101",
                        "authorId": "1",
                        "title": "星云候选快照先行更新 R6SnapshotFirstV3",
                        "summary": "候选快照先行导入摘要",
                        "content": "候选代 g2 先导入 v3 后活动代 g1 仍应正常消费并命中",
                        "thumbnail": null,
                        "isTop": "0",
                        "isComment": "1",
                        "category": {"id": "10", "name": "架构"},
                        "tags": [{"id": "101", "name": "SpringCloud"}, {"id": "106", "name": "R6"}],
                        "createTime": "2026-11-27T08:00:00Z",
                        "publishedAt": "2026-11-27T08:00:00Z",
                        "viewCount": "99",
                        "counterRevision": "3"
                      }
                    }
                    """.formatted(evtUpd900101);

            redis.xadd(streamKey, Map.of("event", upd900101Json));
            redis.xadd(streamKey, Map.of("event", del900103Json));
            ContentStreamConsumer.PollSummary pollR6 = streamConsumer.pollOnce();
            require(pollR6.freshCount() == 2 && pollR6.ackedCount() == 2 && pollR6.dbFailedCount() == 0,
                    "Unexpected R6 pollSummary: " + pollR6);
            require("APPLIED".equals(consumerRepo.findInboxStatus(evtUpd900101).orElse("")),
                    "Expected 900101 v3 inbox=APPLIED");
            require("APPLIED".equals(consumerRepo.findInboxStatus(evtDel900103).orElse("")),
                    "Expected 900103 v5 delete inbox=APPLIED");

            SearchIndexTaskWorker.WorkerCycleSummary workR6 = indexWorker.processOnce();
            require(workR6.appliedCount() == 2 && workR6.failedCount() == 0, "Unexpected workR6: " + workR6);
            esAdmin.refreshIndex(esPhysicalG1);

            contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                    900101L, 3L, "星云候选快照先行更新 R6SnapshotFirstV3", "候选快照先行导入摘要",
                    "候选代 g2 先导入 v3 后活动代 g1 仍应正常消费并命中",
                    10L, "架构", List.of("SpringCloud", "R6"), 99L, 3L, "2026-11-27T08:00:00Z"));
            contentStub.putCurrentHiddenArticle(900103L, "DELETED", 5L);

            String qR6Hit = URLEncoder.encode("R6SnapshotFirstV3", StandardCharsets.UTF_8);
            HttpResponse<String> searchR6Hit = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + qR6Hit)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode sR6HitJson = MAPPER.readTree(searchR6Hit.body());
            require("1".equals(sR6HitJson.path("data").path("total").asText())
                            && "900101".equals(sR6HitJson.path("data").path("rows").get(0).path("id").asText()),
                    "Expected active g1 search hit for 900101 v3: " + searchR6Hit.body());

            HttpResponse<String> searchR6Del = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + q3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode sR6DelJson = MAPPER.readTree(searchR6Del.body());
            require("0".equals(sR6DelJson.path("data").path("total").asText()),
                    "Expected 0 hits for deleted 900103 v5 on active g1: " + searchR6Del.body());

            System.out.println("[LIVE-IT] CHAIN 4 (R6) PASSED: candidate g2 snapshot(900101 v3)+incDelete(900103 v5) first"
                    + " -> active g1 barriers unpolluted -> Stream APPLIED+XACK -> real ES g1 updated"
                    + " -> public search hit(900101 v3)=1 & hit(deleted 900103 v5)=0");

            // =========================================================================
            // CHAIN 5 (SEARCH-03-R7):
            //   (a) Legacy mis-ACKed STALE_IGNORED recovery (PUBLIC v2 + TOMBSTONE v2) -> real ES -> search
            //   (b) Normal late STALE_IGNORED -> NOOP
            //   (c) Standalone WITHDRAWN v7 protected against unactivated candidate g2 (PUBLIC v9 + ARTICLE_COUNTER v7)
            // =========================================================================
            long r7Now = System.currentTimeMillis();
            // Seed 900104 (v1) and 900105 (v1) onto active g1 & real ES first
            String doc900104V1 = MAPPER.writeValueAsString(SearchIndexDocument.upsert(
                    900104L, 1L, "历史旧版文章 R7StaleBefore900104", "旧摘要", "旧正文内容",
                    10L, "架构", List.of("R7"), null, 5L, Instant.parse("2026-11-28T01:00:00Z").toEpochMilli()));
            String doc900105V1 = MAPPER.writeValueAsString(SearchIndexDocument.upsert(
                    900105L, 1L, "待下线历史文章 R7BeforeTombstone900105", "删除前可见摘要", "删除前可见正文",
                    10L, "架构", List.of("R7"), null, 6L, Instant.parse("2026-11-28T01:05:00Z").toEpochMilli()));
            jdbc.update("""
                    INSERT INTO search_article_projection (
                        index_generation, article_id, has_content, content_revision, visibility, counter_revision,
                        view_count, projection_revision, title, summary, content, category_id, category_name,
                        tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                    ) VALUES
                        (1, 900104, 1, 1, 'PUBLIC', 1, 5, 1, '历史旧版文章 R7StaleBefore900104', '旧摘要', '旧正文内容', 10, '架构', '["R7"]', NULL, '0', ?, ?),
                        (1, 900105, 1, 1, 'PUBLIC', 1, 6, 1, '待下线历史文章 R7BeforeTombstone900105', '删除前可见摘要', '删除前可见正文', 10, '架构', '["R7"]', NULL, '0', ?, ?)
                    """,
                    Instant.parse("2026-11-28T01:00:00Z").toEpochMilli(), r7Now - 20_000L,
                    Instant.parse("2026-11-28T01:05:00Z").toEpochMilli(), r7Now - 20_000L);
            jdbc.update("""
                    INSERT INTO search_index_task (
                        index_generation, target_index, article_id, target_projection_revision, operation,
                        payload_json, status, attempts, max_attempts, due_at_ms, lease_until_ms,
                        claim_generation, last_error, es_applied_revision,
                        cache_invalidation_status, cache_invalidation_attempts, updated_at_ms
                    ) VALUES
                        (1, ?, 900104, 1, 'UPSERT', ?, 'PENDING', 0, 5, ?, NULL, 0, NULL, 0, 'NONE', 0, ?),
                        (1, ?, 900105, 1, 'UPSERT', ?, 'PENDING', 0, 5, ?, NULL, 0, NULL, 0, 'NONE', 0, ?)
                    """,
                    esPhysicalG1, doc900104V1, r7Now - 20_000L, r7Now - 20_000L,
                    esPhysicalG1, doc900105V1, r7Now - 20_000L, r7Now - 20_000L);
            SearchIndexTaskWorker.WorkerCycleSummary workR7Seed = indexWorker.processOnce();
            require(workR7Seed.appliedCount() == 2 && workR7Seed.failedCount() == 0,
                    "Unexpected workR7Seed: " + workR7Seed);
            esAdmin.refreshIndex(esPhysicalG1);

            contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                    900104L, 1L, "历史旧版文章 R7StaleBefore900104", "旧摘要", "旧正文内容",
                    10L, "架构", List.of("R7"), 5L, 1L, "2026-11-28T01:00:00Z"));
            contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                    900105L, 1L, "待下线历史文章 R7BeforeTombstone900105", "删除前可见摘要", "删除前可见正文",
                    10L, "架构", List.of("R7"), 6L, 1L, "2026-11-28T01:05:00Z"));

            // Confirm 900105 is initially searchable in real ES before tombstone recovery
            String q900105Before = URLEncoder.encode("R7BeforeTombstone900105", StandardCharsets.UTF_8);
            HttpResponse<String> search900105Before = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + q900105Before)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require("1".equals(MAPPER.readTree(search900105Before.body()).path("data").path("total").asText()),
                    "Expected 900105 visible before R7 tombstone recovery: " + search900105Before.body());

            // Seed legacy mis-ACKed STALE_IGNORED v2 rows:
            //   900104 -> proven pre-R6 candidate g2 snapshot pollution (PUBLIC v2, matching timestamp >= rbJob.createdAtMs)
            //   900105 -> conservatively retained barrier (DELETED v2, no g2 row) while active g1 is still at v1
            long r7PolluteTs = Math.max(r7Now, rbJob.createdAtMs() + 100L);
            String evtR7StalePub = UUID.randomUUID().toString();
            String evtR7StaleDel = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO search_article_projection (
                        index_generation, article_id, has_content, content_revision, visibility, counter_revision,
                        view_count, projection_revision, title, summary, content, category_id, category_name,
                        tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                    ) VALUES (2, 900104, 1, 2, 'PUBLIC', 2, 42, 1, 'Candidate g2 900104 v2', 's', 'c', 10, '架构', '["R7"]', NULL, '0', ?, ?)
                    """, r7PolluteTs, r7PolluteTs);
            jdbc.update("""
                    INSERT INTO search_article_version_barrier (
                        article_id, max_content_revision, last_visibility, updated_at_ms
                    ) VALUES
                        (900104, 2, 'PUBLIC', ?),
                        (900105, 2, 'DELETED', ?)
                    """, r7PolluteTs, r7Now - 10_000L);
            jdbc.update("""
                    INSERT INTO search_event_inbox (
                        event_id, event_type, schema_version, aggregate_type, aggregate_id, source_revision,
                        stream_record_id, status, received_at_ms, processed_at_ms
                    ) VALUES
                        (?, 'ArticleContentChanged', 2, 'ARTICLE', '900104', 2, '900104-2', 'STALE_IGNORED', ?, ?),
                        (?, 'ArticleDeleted', 2, 'ARTICLE', '900105', 2, '900105-2', 'STALE_IGNORED', ?, ?)
                    """,
                    evtR7StalePub, r7Now - 9_000L, r7Now - 9_000L,
                    evtR7StaleDel, r7Now - 9_000L, r7Now - 9_000L);

            contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                    900104L, 2L, "历史误ACK定向恢复验证 R7RecoverPublicV2", "R7 权威快照恢复摘要",
                    "通过 SEARCH-OPS 定向恢复补建活动代 g1 文档并写入真实 ES",
                    10L, "架构", List.of("R7", "Recovery"), 42L, 2L, "2026-11-28T02:00:00Z"));
            contentStub.putCurrentHiddenArticle(900105L, "DELETED", 2L);

            // Discover via GET /internal/v1/ops/consumers/events?status=STALE_IGNORED
            HttpResponse<String> staleListResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/ops/consumers/events?status=STALE_IGNORED&limit=20"))
                            .header("Authorization", "Bearer " + opsToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(staleListResp.statusCode() == 200, "ops STALE_IGNORED list failed: " + staleListResp.statusCode());
            String staleListBody = staleListResp.body();
            require(staleListBody.contains(evtR7StalePub) && staleListBody.contains(evtR7StaleDel),
                    "Expected both legacy STALE_IGNORED events in ops list: " + staleListBody);

            // Recover 900104 (PUBLIC v2) and 900105 (DELETED v2 -> tombstone)
            HttpResponse<String> recPubResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/ops/consumers/" + evtR7StalePub + "/retry"))
                            .header("Authorization", "Bearer " + opsToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"replayRequestId\":\"live-it-r7-pub-1\",\"operator\":\"live-it\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(recPubResp.statusCode() == 200, "R7 public recovery failed: " + recPubResp.body());
            JsonNode recPubJson = MAPPER.readTree(recPubResp.body());
            require("ACCEPTED".equals(recPubJson.path("status").asText())
                            && "UPSERT_ENQUEUED".equals(recPubJson.path("recoveryAction").asText())
                            && "2".equals(recPubJson.path("authoritativeRevision").asText()),
                    "Unexpected R7 public recovery body: " + recPubResp.body());

            HttpResponse<String> recDelResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/ops/consumers/" + evtR7StaleDel + "/retry"))
                            .header("Authorization", "Bearer " + opsToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"replayRequestId\":\"live-it-r7-del-1\",\"operator\":\"live-it\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(recDelResp.statusCode() == 200, "R7 tombstone recovery failed: " + recDelResp.body());
            JsonNode recDelJson = MAPPER.readTree(recDelResp.body());
            require("ACCEPTED".equals(recDelJson.path("status").asText())
                            && "TOMBSTONE_ENQUEUED".equals(recDelJson.path("recoveryAction").asText())
                            && "DELETED".equals(recDelJson.path("authoritativeVisibility").asText()),
                    "Unexpected R7 tombstone recovery body: " + recDelResp.body());

            SearchIndexTaskWorker.WorkerCycleSummary workR7Rec = indexWorker.processOnce();
            require(workR7Rec.appliedCount() == 2 && workR7Rec.failedCount() == 0,
                    "Unexpected workR7Rec: " + workR7Rec);
            esAdmin.refreshIndex(esPhysicalG1);

            String qR7Pub = URLEncoder.encode("R7RecoverPublicV2", StandardCharsets.UTF_8);
            HttpResponse<String> searchR7Pub = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + qR7Pub)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode sR7PubJson = MAPPER.readTree(searchR7Pub.body());
            require("1".equals(sR7PubJson.path("data").path("total").asText())
                            && "900104".equals(sR7PubJson.path("data").path("rows").get(0).path("id").asText()),
                    "Expected recovered 900104 v2 hit in real ES: " + searchR7Pub.body());

            HttpResponse<String> search900105After = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + q900105Before)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require("0".equals(MAPPER.readTree(search900105After.body()).path("data").path("total").asText()),
                    "Expected 0 hits for tombstoned 900105 after R7 recovery: " + search900105After.body());

            HttpResponse<String> esDoc900105 = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG1 + "/_doc/900105")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(esDoc900105.statusCode() == 200, "Expected tombstone doc 900105 in ES: " + esDoc900105.statusCode());
            JsonNode src900105 = MAPPER.readTree(esDoc900105.body()).path("_source");
            require(!src900105.path("searchable").asBoolean(true)
                            && (src900105.path("title").isMissingNode() || src900105.path("title").isNull())
                            && (src900105.path("summary").isMissingNode() || src900105.path("summary").isNull())
                            && (src900105.path("content").isMissingNode() || src900105.path("content").isNull()),
                    "Expected tombstone-only ES doc with 0 body fields for 900105: " + esDoc900105.body());

            // (b) Normal late STALE_IGNORED (v1 <= active g1 v2) -> NOOP
            String evtR7LateStale = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO search_event_inbox (
                        event_id, event_type, schema_version, aggregate_type, aggregate_id, source_revision,
                        stream_record_id, status, received_at_ms, processed_at_ms
                    ) VALUES (?, 'ArticleContentChanged', 2, 'ARTICLE', '900104', 1, '900104-late-1', 'STALE_IGNORED', ?, ?)
                    """, evtR7LateStale, r7Now, r7Now);
            HttpResponse<String> lateNoopResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/ops/consumers/" + evtR7LateStale + "/retry"))
                            .header("Authorization", "Bearer " + opsToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"replayRequestId\":\"live-it-r7-late-1\",\"operator\":\"live-it\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(lateNoopResp.statusCode() == 200
                            && "NOOP".equals(MAPPER.readTree(lateNoopResp.body()).path("status").asText())
                            && "EVENT_STALE_IGNORED".equals(MAPPER.readTree(lateNoopResp.body()).path("reason").asText()),
                    "Expected NOOP(STALE_NOT_ELIGIBLE) for normal late STALE_IGNORED: " + lateNoopResp.body());

            // (c) Standalone WITHDRAWN v7 on 900106 protected against unactivated candidate g2 (PUBLIC v9 + ARTICLE_COUNTER v7)
            jdbc.update("""
                    INSERT INTO search_article_version_barrier (
                        article_id, max_content_revision, last_visibility, updated_at_ms
                    ) VALUES (900106, 7, 'WITHDRAWN', ?)
                    """, r7Now - 30_000L);
            jdbc.update("""
                    INSERT INTO search_article_projection (
                        index_generation, article_id, has_content, content_revision, visibility, counter_revision,
                        view_count, projection_revision, title, summary, content, category_id, category_name,
                        tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                    ) VALUES (2, 900106, 1, 9, 'PUBLIC', 7, 7, 1, 'Candidate g2 v9', 's', 'c', 10, '架构', '[]', NULL, '0', ?, ?)
                    """, r7Now - 10_000L, r7Now - 10_000L);
            jdbc.update("""
                    INSERT INTO search_rebuild_event_log (
                        candidate_generation, event_id, commit_sequence, event_type, aggregate_type, aggregate_id, source_revision,
                        status, applied_at_ms
                    ) VALUES (2, ?, 99, 'ArticleViewCountChanged', 'ARTICLE_COUNTER', '900106', 7, 'APPLIED', ?)
                    """, UUID.randomUUID().toString(), r7Now - 5_000L);

            String evtLate900106 = UUID.randomUUID().toString();
            String late900106Json = """
                    {
                      "eventId": "%s",
                      "eventType": "ArticlePublished",
                      "schemaVersion": 2,
                      "aggregateType": "ARTICLE",
                      "aggregateId": "900106",
                      "sourceRevision": "6",
                      "publicationFactId": "pub-900106-v6",
                      "occurredAt": "2026-11-28T03:00:00Z",
                      "producer": "knode-content",
                      "visibility": "PUBLIC",
                      "payload": {
                        "articleId": "900106",
                        "authorId": "1",
                        "title": "Late Resurrect Attempt 900106",
                        "summary": "must stay blocked",
                        "content": "must stay blocked",
                        "thumbnail": null,
                        "isTop": "0",
                        "isComment": "1",
                        "category": {"id": "10", "name": "架构"},
                        "tags": [],
                        "createTime": "2026-11-28T03:00:00Z",
                        "publishedAt": "2026-11-28T03:00:00Z",
                        "viewCount": "1",
                        "counterRevision": "1"
                      }
                    }
                    """.formatted(evtLate900106);
            redis.xadd(streamKey, Map.of("event", late900106Json));
            ContentStreamConsumer.PollSummary pollR7Protect = streamConsumer.pollOnce();
            require(pollR7Protect.freshCount() == 1 && pollR7Protect.ackedCount() == 1 && pollR7Protect.dbFailedCount() == 0,
                    "Unexpected pollR7Protect: " + pollR7Protect);
            require("STALE_IGNORED".equals(consumerRepo.findInboxStatus(evtLate900106).orElse("")),
                    "Late PUBLIC v6 MUST be STALE_IGNORED by protected standalone WITHDRAWN v7 barrier");
            require(consumerRepo.findVersionBarrier(900106L).orElseThrow().maxContentRevision() == 7L
                            && "WITHDRAWN".equals(consumerRepo.findVersionBarrier(900106L).orElseThrow().lastVisibility().name()),
                    "Standalone WITHDRAWN v7 barrier MUST stay intact");
            require(consumerRepo.findIndexTask(1L, 900106L).isEmpty(),
                    "Protected standalone WITHDRAWN v7 MUST NOT enqueue ES task on active g1");
            HttpResponse<String> esDoc900106 = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG1 + "/_doc/900106")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(esDoc900106.statusCode() == 404,
                    "Protected standalone WITHDRAWN 900106 MUST NOT appear in ES g1, status=" + esDoc900106.statusCode());

            System.out.println("[LIVE-IT] CHAIN 5 (R7) PASSED: legacy STALE_IGNORED list+recover(900104 PUBLIC v2 hit=1, "
                    + "900105 DELETED v2 tombstone searchable=false body=0 hit=0) + normal late STALE_IGNORED=NOOP "
                    + "+ standalone WITHDRAWN v7 protected against candidate g2 PUBLIC v9/ARTICLE_COUNTER v7 (late v6 STALE_IGNORED, ES 404)");
            // =========================================================================
            // CHAIN 6 (SEARCH-03-R8):
            //   (a) Standalone DELETED v7 (900107) with unactivated candidate g2 holding DELETED v7 AND
            //       APPLIED ArticleDeleted v7 log at the EXACT SAME MILLISECOND -> late PUBLIC v6 stays
            //       STALE_IGNORED, barrier stays DELETED v7, 0 tasks, real ES 404.
            //   (b) Lagging Content PUBLIC v1 for 900108 (v2 STALE_IGNORED) -> retryable 503, 0 audit,
            //       inbox stays STALE_IGNORED -> Content catches up to v2 -> SAME replayRequestId recovers
            //       900108 into real ES g1 and public search hits v2!
            // =========================================================================
            long r8SameMs = Math.max(System.currentTimeMillis(), rbJob.createdAtMs() + 500L);
            jdbc.update("""
                    INSERT INTO search_article_version_barrier (
                        article_id, max_content_revision, last_visibility, updated_at_ms
                    ) VALUES (900107, 7, 'DELETED', ?)
                    """, r8SameMs);
            jdbc.update("""
                    INSERT INTO search_article_projection (
                        index_generation, article_id, has_content, content_revision, visibility, counter_revision,
                        view_count, projection_revision, updated_at_ms
                    ) VALUES (2, 900107, 1, 7, 'DELETED', 0, 0, 1, ?)
                    """, r8SameMs);
            jdbc.update("""
                    INSERT INTO search_rebuild_event_log (
                        candidate_generation, event_id, commit_sequence, event_type, aggregate_type, aggregate_id,
                        source_revision, status, applied_at_ms
                    ) VALUES (2, ?, 107, 'ArticleDeleted', 'ARTICLE', '900107', 7, 'APPLIED', ?)
                    """, UUID.randomUUID().toString(), r8SameMs);

            String evtLate900107 = UUID.randomUUID().toString();
            String late900107Json = """
                    {
                      "eventId": "%s",
                      "eventType": "ArticleContentChanged",
                      "schemaVersion": 2,
                      "aggregateType": "ARTICLE",
                      "aggregateId": "900107",
                      "sourceRevision": "6",
                      "publicationFactId": "pub-900107-v6",
                      "occurredAt": "2026-11-28T07:00:00Z",
                      "producer": "knode-content",
                      "visibility": "PUBLIC",
                      "payload": {
                        "articleId": "900107",
                        "authorId": "1",
                        "title": "SameMsZombieAttempt900107",
                        "summary": "must stay blocked",
                        "content": "must stay blocked",
                        "thumbnail": null,
                        "isTop": "0",
                        "isComment": "1",
                        "category": {"id": "10", "name": "架构"},
                        "tags": [],
                        "createTime": "2026-11-28T07:00:00Z",
                        "publishedAt": "2026-11-28T07:00:00Z",
                        "viewCount": "1",
                        "counterRevision": "1"
                      }
                    }
                    """.formatted(evtLate900107);
            redis.xadd(streamKey, Map.of("event", late900107Json));
            ContentStreamConsumer.PollSummary pollR8Protect = streamConsumer.pollOnce();
            require(pollR8Protect.freshCount() == 1 && pollR8Protect.ackedCount() == 1 && pollR8Protect.dbFailedCount() == 0,
                    "Unexpected pollR8Protect: " + pollR8Protect);
            require("STALE_IGNORED".equals(consumerRepo.findInboxStatus(evtLate900107).orElse("")),
                    "Late PUBLIC v6 MUST be STALE_IGNORED despite same-ms candidate ArticleDeleted v7 log");
            require(consumerRepo.findVersionBarrier(900107L).orElseThrow().maxContentRevision() == 7L
                            && "DELETED".equals(consumerRepo.findVersionBarrier(900107L).orElseThrow().lastVisibility().name()),
                    "Standalone DELETED v7 barrier 900107 MUST stay intact");
            require(consumerRepo.findIndexTask(1L, 900107L).isEmpty(),
                    "Protected standalone DELETED 900107 MUST NOT enqueue ES task");
            HttpResponse<String> esDoc900107 = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG1 + "/_doc/900107")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(esDoc900107.statusCode() == 404,
                    "Protected standalone DELETED 900107 MUST NOT appear in ES g1, status=" + esDoc900107.statusCode());

            // (b) Lagging Content v1 -> retryable 503 (0 audit, inbox STALE_IGNORED) -> same key succeeds at v2
            String doc900108V1 = MAPPER.writeValueAsString(SearchIndexDocument.upsert(
                    900108L, 1L, "同键重试旧版文章 R8BeforeLag900108V1", "旧摘要", "旧正文",
                    10L, "架构", List.of("R8"), null, 3L, Instant.parse("2026-11-28T07:05:00Z").toEpochMilli()));
            jdbc.update("""
                    INSERT INTO search_article_projection (
                        index_generation, article_id, has_content, content_revision, visibility, counter_revision,
                        view_count, projection_revision, title, summary, content, category_id, category_name,
                        tag_names_json, thumbnail, is_top, create_time_ms, updated_at_ms
                    ) VALUES (1, 900108, 1, 1, 'PUBLIC', 1, 3, 1, '同键重试旧版文章 R8BeforeLag900108V1', '旧摘要', '旧正文', 10, '架构', '["R8"]', NULL, '0', ?, ?)
                    """, Instant.parse("2026-11-28T07:05:00Z").toEpochMilli(), r8SameMs - 10_000L);
            jdbc.update("""
                    INSERT INTO search_article_version_barrier (
                        article_id, max_content_revision, last_visibility, updated_at_ms
                    ) VALUES (900108, 1, 'PUBLIC', ?)
                    """, r8SameMs - 10_000L);
            jdbc.update("""
                    INSERT INTO search_index_task (
                        index_generation, target_index, article_id, target_projection_revision, operation,
                        payload_json, status, attempts, max_attempts, due_at_ms, lease_until_ms,
                        claim_generation, last_error, es_applied_revision,
                        cache_invalidation_status, cache_invalidation_attempts, updated_at_ms
                    ) VALUES (1, ?, 900108, 1, 'UPSERT', ?, 'PENDING', 0, 5, ?, NULL, 0, NULL, 0, 'NONE', 0, ?)
                    """, esPhysicalG1, doc900108V1, r8SameMs - 10_000L, r8SameMs - 10_000L);
            SearchIndexTaskWorker.WorkerCycleSummary workR8Seed = indexWorker.processOnce();
            require(workR8Seed.appliedCount() == 1 && workR8Seed.failedCount() == 0,
                    "Unexpected workR8Seed: " + workR8Seed);
            esAdmin.refreshIndex(esPhysicalG1);

            String evtR8Lag = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO search_event_inbox (
                        event_id, event_type, schema_version, aggregate_type, aggregate_id, source_revision,
                        stream_record_id, status, received_at_ms, processed_at_ms
                    ) VALUES (?, 'ArticleContentChanged', 2, 'ARTICLE', '900108', 2, '900108-2', 'STALE_IGNORED', ?, ?)
                    """, evtR8Lag, r8SameMs - 5_000L, r8SameMs - 5_000L);

            // First call: Content lags at PUBLIC v1 -> must return retryable 503 with 0 audit & unchanged state
            contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                    900108L, 1L, "同键重试旧版文章 R8BeforeLag900108V1", "旧摘要", "旧正文",
                    10L, "架构", List.of("R8"), 3L, 1L, "2026-11-28T07:05:00Z"));
            String r8RetryBody = "{\"replayRequestId\":\"live-it-r8-lag-1\",\"operator\":\"live-it\"}";
            HttpResponse<String> r8LagResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/ops/consumers/" + evtR8Lag + "/retry"))
                            .header("Authorization", "Bearer " + opsToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(r8RetryBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(r8LagResp.statusCode() == 503
                            && "DEPENDENCY_UNAVAILABLE".equals(MAPPER.readTree(r8LagResp.body()).path("error").path("code").asText())
                            && MAPPER.readTree(r8LagResp.body()).path("error").path("retryable").asBoolean(false),
                    "Expected retryable 503 on lagging v1 snapshot: " + r8LagResp.statusCode() + " body=" + r8LagResp.body());
            require("STALE_IGNORED".equals(consumerRepo.findInboxStatus(evtR8Lag).orElse("")),
                    "Inbox MUST stay STALE_IGNORED after lagging snapshot 503");
            Long r8AuditAfterLag = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = 'live-it-r8-lag-1'", Long.class);
            require(r8AuditAfterLag != null && r8AuditAfterLag == 0L,
                    "Expected 0 audit rows after lagging snapshot 503, got " + r8AuditAfterLag);

            // Second call: Content catches up to PUBLIC v2 -> retry with EXACT SAME replayRequestId succeeds!
            contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                    900108L, 2L, "同键重试追平恢复验证 R8SameKeyRecovered900108V2", "R8 追平恢复摘要",
                    "Content 追到 v2 后原 replayRequestId 唯一有效恢复并写入真实 ES",
                    10L, "架构", List.of("R8", "SameKey"), 18L, 2L, "2026-11-28T07:10:00Z"));
            HttpResponse<String> r8RecResp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/ops/consumers/" + evtR8Lag + "/retry"))
                            .header("Authorization", "Bearer " + opsToken)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(r8RetryBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            require(r8RecResp.statusCode() == 200
                            && "ACCEPTED".equals(MAPPER.readTree(r8RecResp.body()).path("status").asText())
                            && "2".equals(MAPPER.readTree(r8RecResp.body()).path("authoritativeRevision").asText()),
                    "Expected 200 ACCEPTED on same-key retry at v2: " + r8RecResp.body());
            SearchIndexTaskWorker.WorkerCycleSummary workR8Rec = indexWorker.processOnce();
            require(workR8Rec.appliedCount() == 1 && workR8Rec.failedCount() == 0,
                    "Unexpected workR8Rec: " + workR8Rec);
            esAdmin.refreshIndex(esPhysicalG1);

            String qR8 = URLEncoder.encode("R8SameKeyRecovered900108V2", StandardCharsets.UTF_8);
            HttpResponse<String> searchR8 = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/blog/search?keyword=" + qR8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode sR8Json = MAPPER.readTree(searchR8.body());
            require("1".equals(sR8Json.path("data").path("total").asText())
                            && "900108".equals(sR8Json.path("data").path("rows").get(0).path("id").asText()),
                    "Expected recovered 900108 v2 hit in real ES: " + searchR8.body());
            System.out.println("[LIVE-IT] CHAIN 6 (R8) PASSED: same-ms standalone DELETED v7 (900107) preserved (late v6 STALE_IGNORED, ES 404)"
                    + " + lagging v1 (900108) -> 503 retryable (0 audit, inbox STALE_IGNORED)"
                    + " -> same replayRequestId at v2 -> ACCEPTED -> real ES g1 hit=1");
            System.out.println("[LIVE-IT] ALL 6 REAL-MIDDLEWARE CHAINS VERIFIED SUCCESSFULLY! env=" + envName + " alias=" + esAlias);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                }
            }
            userAuthStub.close();
            contentStub.close();

            // Cleanup ONLY isolated Redis keys under knode:<env>:* (NEVER touch any other keys!)
            int deletedRedisKeys = 0;
            try (StatefulRedisConnection<String, String> cleanConn = redisClient.connect()) {
                RedisCommands<String, String> cmd = cleanConn.sync();
                List<String> toDelete = scanKeys(cmd, keyPrefix + "*");
                if (!toDelete.isEmpty()) {
                    deletedRedisKeys = (int) (long) cmd.del(toDelete.toArray(new String[0]));
                }
                List<String> remaining = scanKeys(cmd, keyPrefix + "*");
                System.out.println("[LIVE-IT-CLEANUP] Redis prefix=" + keyPrefix + "* deleted=" + deletedRedisKeys
                        + " remaining=" + remaining.size());
                if (!remaining.isEmpty()) {
                    throw new IllegalStateException("Leftover Redis test keys: " + remaining.size());
                }
            } finally {
                redisClient.shutdown();
            }

            // Cleanup ONLY isolated ES test physical indices g1 & g2 (deleting indices also removes test aliases/fences)
            HttpResponse<String> delEsG1 = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG1)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> delEsG2 = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG2)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> checkAlias = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/_alias/" + esAlias)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> checkIndexG1 = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> checkIndexG2 = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> checkFenceG2 = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/_alias/" + SearchIndexAdminClient.attachFenceAlias(esPhysicalG2))).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            System.out.println("[LIVE-IT-CLEANUP] ES deleteG1=" + delEsG1.statusCode()
                    + " deleteG2=" + delEsG2.statusCode()
                    + " aliasAfterStatus=" + checkAlias.statusCode()
                    + " indexG1AfterStatus=" + checkIndexG1.statusCode()
                    + " indexG2AfterStatus=" + checkIndexG2.statusCode()
                    + " fenceG2AfterStatus=" + checkFenceG2.statusCode());
            if (checkAlias.statusCode() != 404
                    || checkIndexG1.statusCode() != 404
                    || checkIndexG2.statusCode() != 404
                    || checkFenceG2.statusCode() != 404) {
                throw new IllegalStateException("ES test resource cleanup incomplete");
            }
        }
    }

    private static RedisClient createRedisClient(String host, int port, int db, String password) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(db)
                .withTimeout(Duration.ofSeconds(5));
        if (password != null && !password.isEmpty()) {
            builder.withPassword(password.toCharArray());
        }
        return RedisClient.create(builder.build());
    }

    private static List<String> scanKeys(RedisCommands<String, String> cmd, String pattern) {
        List<String> result = new ArrayList<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = ScanArgs.Builder.matches(pattern).limit(200);
        do {
            KeyScanCursor<String> page = cmd.scan(cursor, args);
            result.addAll(page.getKeys());
            cursor = page;
        } while (!cursor.isFinished());
        return result;
    }

    private static double counterValue(MeterRegistry registry, String name, String tagKey, String tagVal) {
        Counter counter = registry.find(name).tag(tagKey, tagVal).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String requireEnv(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + key);
        }
        return val.trim();
    }

    private static String envOrDefault(String key, String fallback) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }

    private static void require(boolean cond, String message) {
        if (!cond) {
            throw new IllegalStateException("Verification assertion failed: " + message);
        }
    }

    @TestConfiguration
    @Profile("real-middleware-it")
    static class LiveTestTokenConfig {
        @Bean
        @Primary
        ServiceTokenClient liveTestServiceTokenClient() {
            return new ServiceTokenClient() {
                @Override
                public String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
                    return "it-content-token";
                }

                @Override
                public void invalidate(String audience, List<String> permissions) {
                }
            };
        }
    }
}
