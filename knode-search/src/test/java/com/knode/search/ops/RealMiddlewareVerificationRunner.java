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
 * Standalone live-middleware verification runner for SEARCH-04.
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
        String envName = "it-search04-" + runSuffix;
        String keyPrefix = "knode:" + envName + ":";
        String streamKey = keyPrefix + "v1:events:content";
        String consumerGroup = "knode-search-v1";
        String esAlias = "knode_it_search04_" + runSuffix + "_articles";
        String esPhysicalG1 = esAlias + "_g1";
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

            System.out.println("[LIVE-IT] ALL 3 REAL-MIDDLEWARE CHAINS VERIFIED SUCCESSFULLY! env=" + envName + " alias=" + esAlias);
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

            // Cleanup ONLY isolated ES test physical index (deleting index also removes its test alias)
            HttpResponse<String> delEs = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG1)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> checkAlias = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/_alias/" + esAlias)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> checkIndex = http.send(
                    HttpRequest.newBuilder(URI.create(esUrl + "/" + esPhysicalG1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            System.out.println("[LIVE-IT-CLEANUP] ES deleteStatus=" + delEs.statusCode()
                    + " aliasAfterStatus=" + checkAlias.statusCode()
                    + " indexAfterStatus=" + checkIndex.statusCode());
            if (checkAlias.statusCode() != 404 || checkIndex.statusCode() != 404) {
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
