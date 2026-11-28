package com.knode.search.rebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knode.search.KnodeSearchApplication;
import com.knode.search.client.ServiceTokenClient;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.ArticleVersionBarrier;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexOperation;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.consumer.stream.ContentStreamConsumer;
import com.knode.search.model.QueryBudget;
import com.knode.search.support.LoopbackContentSnapshotStub;
import com.knode.search.support.LoopbackRedisStub;
import com.knode.search.support.LoopbackUserAdminAuthStub;
import com.knode.search.support.StatefulElasticsearchStub;
import com.knode.search.support.TestJwtSigner;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEARCH-03-R8 regression suite:
 * 1. Same-millisecond timestamp on an unactivated candidate's APPLIED ArticleDeleted log MUST NOT
 *    clear a standalone historical DELETED barrier when the active generation has no usable content
 *    projection (neither absent row nor counter-only has_content=0 stub) and inbox has no entry.
 *    Also preserves counter same-revision isolation from content barriers.
 * 2. During SEARCH-OPS STALE_IGNORED recovery, a lagging Content snapshot (PUBLIC or known-revision
 *    hidden WITHDRAWN/DELETED/DRAFT) whose revision does not cover inbox.sourceRevision, active
 *    projection, and effective barrier MUST fail with retryable 503 DEPENDENCY_UNAVAILABLE, rolling
 *    back inbox/projection/barrier/task and leaving NO terminal audit row so the exact same
 *    replayRequestId succeeds once Content catches up.
 */
@SpringBootTest(
        classes = {KnodeSearchApplication.class, SearchRebuildR8RegressionsTest.TestTokenConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SearchRebuildR8RegressionsTest {
    private static final String ENV = "it-search03-r8-unit";
    private static final String ES_ALIAS = "knode_it_r8_articles_search";
    private static final String G1 = ES_ALIAS + "_g1";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String CONSUMER_GROUP = "knode-search-v1";
    private static final String DB_NAME = "search_r8_" + UUID.randomUUID().toString().replace("-", "");
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
    private ContentStreamConsumer streamConsumer;

    @Autowired
    private SearchIndexTaskWorker indexWorker;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startStubs() throws Exception {
        userJwtSigner = TestJwtSigner.generate("knode-user-k1");
        serviceJwtSigner = TestJwtSigner.generate("knode-svc-k1");
        redisStub = new LoopbackRedisStub();
        esStub = new StatefulElasticsearchStub();
        contentStub = new LoopbackContentSnapshotStub();
        contentStub.setExpectedBearerToken("r8-content-token");
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
        registry.add("knode.search.rebuild.instance-id", () -> "r8-inst-1");

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
        jdbcTemplate.execute("DELETE FROM search_rebuild_event_log");
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
                """, ES_ALIAS, G1, now);

        redisStub.reset();
        redisStub.createGroup(STREAM_KEY, CONSUMER_GROUP);
        esStub.reset();
        esStub.setAlias(ES_ALIAS, G1);
        contentStub.clearInterceptor();
        contentStub.clearRequests();
        contentStub.clearCurrentArticles();
        contentStub.setLivePublicArticles(List.of());
        userAuthStub.resetCalls();
        userAuthStub.setAuthority("1", List.of("admin"), List.of("*:*:*"));
    }

    /**
     * Acceptance Criterion 1 (Runtime):
     * Standalone DELETED v7 barrier with NO usable active-generation projection (tested for both
     * completely absent active projection AND counter-only has_content=0 stub) and NO inbox entry.
     * Unactivated candidate g2 ALSO holds DELETED v7 and an APPLIED ArticleDeleted v7 rebuild log
     * whose applied_at_ms is IDENTICALLY EQUAL to barrier.updated_at_ms.
     * Active g1 receives late PUBLIC v6 -> MUST remain STALE_IGNORED, barrier stays DELETED v7,
     * ZERO content index tasks, ZERO ES docs. Counter same-revision (ARTICLE_COUNTER v7) also
     * remains isolated from content barriers.
     */
    @Test
    void sameMillisecondCandidateDeleteDoesNotClearStandaloneDeleteBarrierInRuntime() throws Exception {
        long tJob = 1700000010000L;
        long tSameMs = tJob + 2_500L;

        insertUnactivatedCandidateJob("rb-r8-same-ms-g2", 2L, "FAILED", 5L, tJob, tSameMs + 1_000L);

        // 8001: standalone DELETED v7 (no active g1 row, no inbox) + candidate g2 DELETED v7 + same-ms ArticleDeleted v7 log.
        jdbcTemplate.update("""
                INSERT INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
                VALUES (8001, 7, 'DELETED', ?)
                """, tSameMs);
        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, updated_at_ms
                ) VALUES (2, 8001, 1, 7, 'DELETED', 0, 0, 1, ?)
                """, tSameMs);
        jdbcTemplate.update("""
                INSERT INTO search_rebuild_event_log (
                    candidate_generation, event_id, commit_sequence, event_type,
                    aggregate_type, aggregate_id, source_revision, status, applied_at_ms
                ) VALUES (2, ?, 1, 'ArticleDeleted', 'ARTICLE', '8001', 7, 'APPLIED', ?)
                """, UUID.randomUUID().toString(), tSameMs);

        // 8002: standalone DELETED v7 (active g1 has ONLY counter-only has_content=0 stub, no inbox)
        // + candidate g2 DELETED v7 + same-ms ArticleDeleted v7 log.
        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, updated_at_ms
                ) VALUES
                    (1, 8002, 0, 0, 'NONE', 3, 30, 1, ?),
                    (2, 8002, 1, 7, 'DELETED', 0, 0, 1, ?)
                """, tJob - 1_000L, tSameMs);
        jdbcTemplate.update("""
                INSERT INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
                VALUES (8002, 7, 'DELETED', ?)
                """, tSameMs);
        jdbcTemplate.update("""
                INSERT INTO search_rebuild_event_log (
                    candidate_generation, event_id, commit_sequence, event_type,
                    aggregate_type, aggregate_id, source_revision, status, applied_at_ms
                ) VALUES (2, ?, 2, 'ArticleDeleted', 'ARTICLE', '8002', 7, 'APPLIED', ?)
                """, UUID.randomUUID().toString(), tSameMs);

        // 8003: standalone WITHDRAWN v7 + candidate g2 PUBLIC v9 + same-ms ARTICLE_COUNTER v7 log.
        jdbcTemplate.update("""
                INSERT INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
                VALUES (8003, 7, 'WITHDRAWN', ?)
                """, tSameMs);
        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms
                ) VALUES (2, 8003, 1, 9, 'PUBLIC', 7, 700, 2, 'Cand8003', 'S', 'B', ?)
                """, tSameMs);
        jdbcTemplate.update("""
                INSERT INTO search_rebuild_event_log (
                    candidate_generation, event_id, commit_sequence, event_type,
                    aggregate_type, aggregate_id, source_revision, status, applied_at_ms
                ) VALUES (2, ?, 3, 'ArticleCounterChanged', 'ARTICLE_COUNTER', '8003', 7, 'APPLIED', ?)
                """, UUID.randomUUID().toString(), tSameMs);

        String evt8001 = UUID.randomUUID().toString();
        String evt8002 = UUID.randomUUID().toString();
        String evt8003 = UUID.randomUUID().toString();
        redisStub.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                evt8001, "ArticleContentChanged", 8001L, 6L, "Zombie8001V6", "S6", "B6", 10L, 1L)));
        redisStub.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                evt8002, "ArticleContentChanged", 8002L, 6L, "Zombie8002V6", "S6", "B6", 10L, 1L)));
        redisStub.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                evt8003, "ArticleContentChanged", 8003L, 6L, "Zombie8003V6", "S6", "B6", 10L, 1L)));

        streamConsumer.pollOnce();
        indexWorker.processOnce();

        assertThat(consumerRepository.findInboxStatus(evt8001)).contains("STALE_IGNORED");
        assertThat(consumerRepository.findInboxStatus(evt8002)).contains("STALE_IGNORED");
        assertThat(consumerRepository.findInboxStatus(evt8003)).contains("STALE_IGNORED");

        assertThat(consumerRepository.findVersionBarrier(8001L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(7L, ArticleVisibility.DELETED);
        assertThat(consumerRepository.findVersionBarrier(8002L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(7L, ArticleVisibility.DELETED);
        assertThat(consumerRepository.findVersionBarrier(8003L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(7L, ArticleVisibility.WITHDRAWN);

        assertThat(consumerRepository.findIndexTask(1L, 8001L)).isEmpty();
        assertThat(consumerRepository.findIndexTask(1L, 8002L)).isEmpty();
        assertThat(consumerRepository.findIndexTask(1L, 8003L)).isEmpty();

        assertThat(esStub.getWrittenDocument(G1, "8001")).isNull();
        assertThat(esStub.getWrittenDocument(G1, "8002")).isNull();
        assertThat(esStub.getWrittenDocument(G1, "8003")).isNull();

        ArticleProjectionRecord counterStub8002 = consumerRepository.findProjection(1L, 8002L).orElseThrow();
        assertThat(counterStub8002.hasContent()).isFalse();
        assertThat(counterStub8002.contentRevision()).isZero();
        assertThat(counterStub8002.counterRevision()).isEqualTo(3L);
        assertThat(counterStub8002.viewCount()).isEqualTo(30L);
    }

    /**
     * Acceptance Criterion 2:
     * Active g1 at PUBLIC v1, legacy mis-ACKed v2 STALE_IGNORED in inbox.
     * First attempt: Content lags at v1 (both PUBLIC v1 and hidden WITHDRAWN/DELETED v1).
     * Must fail with retryable 503 DEPENDENCY_UNAVAILABLE, keep inbox STALE_IGNORED, leave active
     * projection/barrier/task unchanged, and commit NO terminal audit row for replayRequestId.
     * Once Content catches up to v2, retrying with the EXACT SAME request and replayRequestId
     * performs a single effective recovery (verified sequentially & concurrently), pushes v2 into
     * DB & active ES physical index so public search hits v2, writes tombstones only for v2
     * WITHDRAWN/DELETED/MISSING, and keeps normal late STALE_IGNORED strictly NOOP.
     */
    @Test
    void laggingContentSnapshotKeepsStaleIgnoredEligibleAndRetryableWithSameKey() throws Exception {
        long t0 = System.currentTimeMillis() - 30_000L;
        long tJob = t0 + 5_000L;
        long tPollute = tJob + 1_000L;

        seedActivePublicV1(8101L, "R8LagPublic8101V1", t0);
        seedActivePublicV1(8102L, "R8LagPolluted8102V1", t0);
        seedActivePublicV1(8103L, "R8LagWithdrawn8103V1", t0);
        seedActivePublicV1(8104L, "R8LagDeleted8104V1", t0);
        seedActivePublicV1(8105L, "R8Missing8105V1", t0);

        // Give 8102 a pre-SQL proven candidate-polluted barrier at v2 (active g1 is still v1).
        insertUnactivatedCandidateJob("rb-r8-lag-g2", 2L, "FAILED", 2L, tJob, tPollute + 1_000L);
        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms
                ) VALUES (2, 8102, 1, 2, 'PUBLIC', 2, 22, 1, 'Cand8102V2', 'S2', 'B2', ?)
                """, tPollute);
        jdbcTemplate.update("""
                UPDATE search_article_version_barrier
                SET max_content_revision = 2, last_visibility = 'PUBLIC', updated_at_ms = ?
                WHERE article_id = 8102
                """, tPollute);

        String evt8101 = UUID.randomUUID().toString();
        String evt8102 = UUID.randomUUID().toString();
        String evt8103 = UUID.randomUUID().toString();
        String evt8104 = UUID.randomUUID().toString();
        String evt8105 = UUID.randomUUID().toString();
        insertArticleInbox(evt8101, "ArticleContentChanged", 8101L, 2L, "PUBLIC", "STALE_IGNORED", tPollute + 500L);
        insertArticleInbox(evt8102, "ArticleContentChanged", 8102L, 2L, "PUBLIC", "STALE_IGNORED", tPollute + 500L);
        insertArticleInbox(evt8103, "ArticleVisibilityChanged", 8103L, 2L, "WITHDRAWN", "STALE_IGNORED", tPollute + 500L);
        insertArticleInbox(evt8104, "ArticleDeleted", 8104L, 2L, "DELETED", "STALE_IGNORED", tPollute + 500L);
        insertArticleInbox(evt8105, "ArticleContentChanged", 8105L, 2L, "PUBLIC", "STALE_IGNORED", tPollute + 500L);

        // Record baseline index task state (DONE, target_projection_revision=1, claim_generation=1) before lag attempts.
        Map<Long, SearchIndexTaskRecord> baselineTasks = Map.of(
                8101L, consumerRepository.findIndexTask(1L, 8101L).orElseThrow(),
                8102L, consumerRepository.findIndexTask(1L, 8102L).orElseThrow(),
                8103L, consumerRepository.findIndexTask(1L, 8103L).orElseThrow(),
                8104L, consumerRepository.findIndexTask(1L, 8104L).orElseThrow()
        );

        String opsToken = serviceJwtSigner.signServiceToken(
                "knode-content", "knode-search", List.of("ops:read", "ops:retry"), 120);
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + opsToken,
                "Content-Type", "application/json"
        );

        // Phase A: Content still lags at v1 (both PUBLIC v1 and hidden WITHDRAWN/DELETED v1).
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                8101L, 1L, "R8LagPublic8101V1", "Summary V1", "Body V1",
                10L, "Tech", List.of("R8"), 5L, 1L, "2026-11-28T01:00:00Z"));
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                8102L, 1L, "R8LagPolluted8102V1", "Summary V1", "Body V1",
                10L, "Tech", List.of("R8"), 5L, 1L, "2026-11-28T01:00:00Z"));
        contentStub.putCurrentHiddenArticle(8103L, "WITHDRAWN", 1L);
        contentStub.putCurrentHiddenArticle(8104L, "DELETED", 1L);

        Map<Long, String> eventByArticle = Map.of(
                8101L, evt8101,
                8102L, evt8102,
                8103L, evt8103,
                8104L, evt8104
        );
        for (Map.Entry<Long, String> entry : eventByArticle.entrySet()) {
            long id = entry.getKey();
            String replayKey = "r8-lag-" + id;
            String body = "{\"replayRequestId\":\"" + replayKey + "\",\"operator\":\"ops-r8\"}";
            HttpResponse<String> resp = send("POST", "/internal/v1/ops/consumers/" + entry.getValue() + "/retry", body, headers);

            assertThat(resp.statusCode())
                    .as("Lagging Content v1 for article %s must return retryable 503", id)
                    .isEqualTo(503);
            JsonNode err = MAPPER.readTree(resp.body()).path("error");
            assertThat(err.path("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
            assertThat(err.path("retryable").asBoolean()).isTrue();

            assertThat(consumerRepository.findInboxStatus(entry.getValue())).contains("STALE_IGNORED");
            ArticleProjectionRecord proj = consumerRepository.findProjection(1L, id).orElseThrow();
            assertThat(proj.contentRevision()).isEqualTo(1L);
            assertThat(proj.projectionRevision()).isEqualTo(1L);
            assertThat(proj.visibility()).isEqualTo(ArticleVisibility.PUBLIC);

            SearchIndexTaskRecord taskAfter = consumerRepository.findIndexTask(1L, id).orElseThrow();
            SearchIndexTaskRecord taskBefore = baselineTasks.get(id);
            assertThat(taskAfter.status()).isEqualTo(taskBefore.status());
            assertThat(taskAfter.targetProjectionRevision()).isEqualTo(taskBefore.targetProjectionRevision());
            assertThat(taskAfter.claimGeneration()).isEqualTo(taskBefore.claimGeneration());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = ?",
                    Long.class, replayKey)).isZero();
        }
        // 8102's barrier must also remain untouched (rolled back) until a valid recovery commits.
        assertThat(consumerRepository.findVersionBarrier(8102L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(2L, ArticleVisibility.PUBLIC);

        // Also verify same-revision visibility lag: inbox is ArticleDeleted v2 (8104), Content lags at PUBLIC v2 -> 503!
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                8104L, 2L, "LaggingPublicV2BehindDelete8104", "S2", "B2",
                10L, "Tech", List.of("R8"), 5L, 2L, "2026-11-28T01:30:00Z"));
        HttpResponse<String> visLag8104 = send("POST", "/internal/v1/ops/consumers/" + evt8104 + "/retry",
                "{\"replayRequestId\":\"r8-lag-8104\",\"operator\":\"ops-r8\"}", headers);
        assertThat(visLag8104.statusCode()).isEqualTo(503);
        assertThat(MAPPER.readTree(visLag8104.body()).path("error").path("code").asText())
                .isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(consumerRepository.findInboxStatus(evt8104)).contains("STALE_IGNORED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = 'r8-lag-8104'",
                Long.class)).isZero();

        // Phase B: Content catches up to v2 -> retry with the EXACT SAME request and replayRequestId!
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                8101L, 2L, "R8RecoveredPublic8101V2", "Summary V2", "Body 8101 V2",
                10L, "Tech", List.of("R8"), 15L, 2L, "2026-11-28T02:00:00Z"));
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                8102L, 2L, "R8RecoveredPolluted8102V2", "Summary V2", "Body 8102 V2",
                10L, "Tech", List.of("R8"), 20L, 2L, "2026-11-28T02:00:00Z"));
        contentStub.putCurrentHiddenArticle(8103L, "WITHDRAWN", 2L);
        contentStub.putCurrentHiddenArticle(8104L, "DELETED", 2L);
        contentStub.removeCurrentArticle(8105L);

        // B1: Sequential same-key retry for 8101 + duplicate replay + conflict check.
        String body8101 = "{\"replayRequestId\":\"r8-lag-8101\",\"operator\":\"ops-r8\"}";
        HttpResponse<String> rec8101 = send("POST", "/internal/v1/ops/consumers/" + evt8101 + "/retry", body8101, headers);
        assertThat(rec8101.statusCode()).isEqualTo(200);
        JsonNode rec8101Json = MAPPER.readTree(rec8101.body());
        assertThat(rec8101Json.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(rec8101Json.path("requeued").asText()).isEqualTo("1");
        assertThat(rec8101Json.path("recoveryAction").asText()).isEqualTo("UPSERT_ENQUEUED");
        assertThat(rec8101Json.path("authoritativeRevision").asText()).isEqualTo("2");

        HttpResponse<String> dup8101 = send("POST", "/internal/v1/ops/consumers/" + evt8101 + "/retry", body8101, headers);
        assertThat(dup8101.statusCode()).isEqualTo(200);
        assertThat(dup8101.body()).isEqualTo(rec8101.body());

        HttpResponse<String> conflict8101 = send("POST", "/internal/v1/ops/consumers/" + evt8101 + "/retry",
                "{\"replayRequestId\":\"r8-lag-8101\",\"operator\":\"other-ops\"}", headers);
        assertThat(conflict8101.statusCode()).isEqualTo(409);

        // B2: Concurrent same-key retry for 8102 using the EXACT SAME r8-lag-8102!
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            String body8102 = "{\"replayRequestId\":\"r8-lag-8102\",\"operator\":\"ops-r8\"}";
            Future<HttpResponse<String>> f1 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/" + evt8102 + "/retry", body8102, headers);
            });
            Future<HttpResponse<String>> f2 = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/" + evt8102 + "/retry", body8102, headers);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            HttpResponse<String> r1 = f1.get(10, TimeUnit.SECONDS);
            HttpResponse<String> r2 = f2.get(10, TimeUnit.SECONDS);
            assertThat(r1.statusCode()).isEqualTo(200);
            assertThat(r2.statusCode()).isEqualTo(200);
            assertThat(r1.body()).isEqualTo(r2.body());
            assertThat(MAPPER.readTree(r1.body()).path("status").asText()).isEqualTo("ACCEPTED");
            assertThat(MAPPER.readTree(r1.body()).path("authoritativeRevision").asText()).isEqualTo("2");
        } finally {
            pool.shutdownNow();
        }

        // B3: Hidden v2 (WITHDRAWN & DELETED) and MISSING tombstone recoveries with same keys.
        for (Map.Entry<String, String> entry : Map.of(
                evt8103, "r8-lag-8103",
                evt8104, "r8-lag-8104",
                evt8105, "r8-missing-8105"
        ).entrySet()) {
            HttpResponse<String> resp = send("POST", "/internal/v1/ops/consumers/" + entry.getKey() + "/retry",
                    "{\"replayRequestId\":\"" + entry.getValue() + "\",\"operator\":\"ops-r8\"}", headers);
            assertThat(resp.statusCode()).isEqualTo(200);
            JsonNode j = MAPPER.readTree(resp.body());
            assertThat(j.path("status").asText()).isEqualTo("ACCEPTED");
            assertThat(j.path("requeued").asText()).isEqualTo("1");
            assertThat(j.path("recoveryAction").asText()).isEqualTo("TOMBSTONE_ENQUEUED");
        }

        // Verify zero duplicate writes to audit / projection / task / inbox.
        for (long id : List.of(8101L, 8102L, 8103L, 8104L)) {
            String key = "r8-lag-" + id;
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = ?",
                    Long.class, key)).isEqualTo(1L);
            assertThat(consumerRepository.findProjection(1L, id).orElseThrow().projectionRevision()).isEqualTo(2L);
            assertThat(consumerRepository.findIndexTask(1L, id).orElseThrow().targetProjectionRevision()).isEqualTo(2L);
            assertThat(consumerRepository.findInboxStatus(eventByArticle.get(id))).contains("APPLIED");
        }

        // Run worker -> ES active physical index updated -> public search hits v2 for 8101 & 8102, 0 hits for tombstones!
        SearchIndexTaskWorker.WorkerCycleSummary work = indexWorker.processOnce();
        assertThat(work.appliedCount()).isEqualTo(5);
        assertThat(work.failedCount()).isZero();

        for (Map.Entry<Long, String> e : Map.of(
                8101L, "R8RecoveredPublic8101V2",
                8102L, "R8RecoveredPolluted8102V2"
        ).entrySet()) {
            HttpResponse<String> searchResp = send("GET",
                    "/api/blog/search?keyword=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8), null, Map.of());
            assertThat(searchResp.statusCode()).isEqualTo(200);
            JsonNode data = MAPPER.readTree(searchResp.body()).path("data");
            assertThat(data.path("total").asText()).isEqualTo("1");
            assertThat(data.path("rows").get(0).path("id").asText()).isEqualTo(Long.toString(e.getKey()));
            assertThat(data.path("rows").get(0).path("title").asText()).isEqualTo(e.getValue());
        }

        for (long id : List.of(8103L, 8104L, 8105L)) {
            StatefulElasticsearchStub.StoredDoc doc = esStub.getRefreshedDocument(G1, Long.toString(id));
            assertThat(doc).isNotNull();
            JsonNode src = MAPPER.readTree(doc.sourceJson());
            assertThat(src.path("searchable").asBoolean()).isFalse();
            assertThat(src.has("title")).isFalse();
            assertThat(src.has("summary")).isFalse();
            assertThat(src.has("content")).isFalse();
            assertThat(src.has("categoryId")).isFalse();
            assertThat(src.has("tagNames")).isFalse();
            assertThat(consumerRepository.findIndexTask(1L, id).orElseThrow().operation())
                    .isEqualTo(SearchIndexOperation.TOMBSTONE);
        }
        assertThat(consumerRepository.findVersionBarrier(8103L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(2L, ArticleVisibility.WITHDRAWN);
        assertThat(consumerRepository.findVersionBarrier(8104L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(2L, ArticleVisibility.DELETED);
        assertThat(consumerRepository.findVersionBarrier(8105L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(2L, ArticleVisibility.DELETED);

        for (String kw : List.of("R8LagWithdrawn8103V1", "R8LagDeleted8104V1", "R8Missing8105V1")) {
            HttpResponse<String> s = send("GET", "/api/blog/search?keyword=" + kw, null, Map.of());
            assertThat(MAPPER.readTree(s.body()).path("data").path("total").asText()).isEqualTo("0");
        }

        // Phase C: Normal late STALE_IGNORED event remains strictly NOOP with zero Content calls.
        String evtNormalLate = UUID.randomUUID().toString();
        insertArticleInbox(evtNormalLate, "ArticleContentChanged", 8101L, 1L, "PUBLIC", "STALE_IGNORED", tPollute + 1_000L);
        contentStub.clearRequests();
        HttpResponse<String> lateResp = send("POST", "/internal/v1/ops/consumers/" + evtNormalLate + "/retry",
                "{\"replayRequestId\":\"r8-normal-late\",\"operator\":\"ops-r8\"}", headers);
        assertThat(lateResp.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(lateResp.body()).path("status").asText()).isEqualTo("NOOP");
        assertThat(MAPPER.readTree(lateResp.body()).path("reason").asText()).isEqualTo("EVENT_STALE_IGNORED");
        assertThat(contentStub.requests()).isEmpty();
    }

    private void seedActivePublicV1(long articleId, String title, long ts) throws Exception {
        String evtV1 = UUID.randomUUID().toString();
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                articleId, 1L, title, "Summary V1", "Body V1 " + title,
                10L, "Tech", List.of("Init"), 5L, 1L, "2026-11-27T00:00:00Z"));
        redisStub.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                evtV1, "ArticlePublished", articleId, 1L, title, "Summary V1", "Body V1 " + title, 5L, 1L)));
        streamConsumer.pollOnce();
        indexWorker.processOnce();
        assertThat(consumerRepository.findProjection(1L, articleId).orElseThrow().contentRevision()).isEqualTo(1L);
        assertThat(esStub.getRefreshedDocument(G1, Long.toString(articleId))).isNotNull();
    }

    private void insertUnactivatedCandidateJob(
            String jobId, long candidateGen, String phase, long snapshotItemsImported, long createdAtMs, long updatedAtMs) {
        jdbcTemplate.update("""
                INSERT INTO search_rebuild_job (
                    job_id, alias_name, active_lock_key, client_request_id,
                    snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                    last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                    source_generation, source_physical_index, candidate_generation, candidate_physical_index,
                    phase, lease_owner, lease_until_ms, lease_generation,
                    alias_switch_uncertain, switch_attempts, created_at_ms, updated_at_ms
                ) VALUES (
                    ?, ?, NULL, ?,
                    1, 1, ?,
                    1, 1, 1,
                    1, ?, ?, ?,
                    ?, 'old-inst', ?, 1,
                    0, 0, ?, ?
                )
                """,
                jobId, ES_ALIAS, "req-" + jobId, snapshotItemsImported,
                G1, candidateGen, ES_ALIAS + "_g" + candidateGen,
                phase, updatedAtMs, createdAtMs, updatedAtMs);
    }

    private void insertArticleInbox(
            String eventId, String eventType, long articleId, long rev, String visibility, String status, long ts) {
        jdbcTemplate.update("""
                INSERT INTO search_event_inbox (
                    event_id, stream_record_id, event_type, schema_version, aggregate_type, aggregate_id,
                    source_revision, visibility, status, received_at_ms, processed_at_ms
                ) VALUES (?, '50-0', ?, 2, 'ARTICLE', ?, ?, ?, ?, ?, ?)
                """, eventId, eventType, Long.toString(articleId), rev, visibility, status, ts, ts);
    }

    private static String publicArticleEventJson(
            String eventId,
            String eventType,
            long articleId,
            long sourceRevision,
            String title,
            String summary,
            String content,
            long viewCount,
            long counterRevision) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", eventType);
        root.put("schemaVersion", 2);
        root.put("producer", "knode-content");
        root.put("occurredAt", "2026-11-28T01:00:00Z");
        root.put("aggregateType", "ARTICLE");
        root.put("visibility", "PUBLIC");
        root.put("publicationFactId", "pub-" + articleId + "-v" + sourceRevision);
        root.put("aggregateId", Long.toString(articleId));
        root.put("sourceRevision", Long.toString(sourceRevision));
        ObjectNode payload = root.putObject("payload");
        payload.put("articleId", Long.toString(articleId));
        payload.put("authorId", "9001");
        payload.put("title", title);
        payload.put("summary", summary);
        payload.put("content", content);
        payload.put("thumbnail", "https://cdn.example.com/" + articleId + ".png");
        payload.put("isTop", "0");
        payload.put("isComment", "1");
        ObjectNode cat = payload.putObject("category");
        cat.put("id", "10");
        cat.put("name", "Tech");
        ArrayNode tags = payload.putArray("tags");
        ObjectNode tag = tags.addObject();
        tag.put("id", "100");
        tag.put("name", "R8");
        payload.put("createTime", "2026-10-01T00:00:00Z");
        payload.put("publishedAt", "2026-10-01T00:00:00Z");
        payload.put("viewCount", Long.toString(viewCount));
        payload.put("counterRevision", Long.toString(counterRevision));
        return MAPPER.writeValueAsString(root);
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
                    return "r8-content-token";
                }

                @Override
                public void invalidate(String audience, List<String> permissions) {
                }
            };
        }
    }
}
