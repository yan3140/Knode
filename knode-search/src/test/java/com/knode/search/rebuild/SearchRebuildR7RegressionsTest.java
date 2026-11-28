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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEARCH-03-R7 regression suite:
 * 1. Protects valid standalone / inbox-backed historical barriers against false candidate attribution
 *    (specifically counter vs content revision separation and same-version candidate tombstones).
 * 2. Recovers legacy pre-R6 mis-ACKed STALE_IGNORED article updates via audited SEARCH-OPS recovery
 *    against authoritative Content state (PUBLIC upsert + WITHDRAWN/DELETED/MISSING tombstone),
 *    preserving idempotency, concurrency safety, no-regression, normal STALE_IGNORED NOOP, and
 *    locatable failure states.
 */
@SpringBootTest(
        classes = {KnodeSearchApplication.class, SearchRebuildR7RegressionsTest.TestTokenConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SearchRebuildR7RegressionsTest {
    private static final String ENV = "it-search03-r7";
    private static final String ES_ALIAS = "knode_it_r7_articles_search";
    private static final String G1 = ES_ALIAS + "_g1";
    private static final String G2 = ES_ALIAS + "_g2";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String CONSUMER_GROUP = "knode-search-v1";
    private static final String DB_NAME = "search_r7_" + UUID.randomUUID().toString().replace("-", "");
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
        contentStub.setExpectedBearerToken("r7-content-token");
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
        registry.add("knode.search.rebuild.instance-id", () -> "r7-inst-1");

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
     * Standalone WITHDRAWN v7 barrier + unactivated candidate g2 having PUBLIC v9 and ONLY
     * an ARTICLE_COUNTER v7 APPLIED log (even at the same timestamp!).
     * Active g1 receives late PUBLIC v6 -> MUST stay STALE_IGNORED, barrier MUST stay WITHDRAWN v7,
     * ZERO stale content index tasks created.
     * Also verifies standalone DELETED v7 backed by APPLIED ArticleDeleted v7 inbox survives even
     * when unactivated candidate g2 has the same DELETED v7 tombstone.
     */
    @Test
    void runtimeBarrierProtectionPreservesStandaloneWithdrawnAgainstCandidateCounterLogAndInboxBackedDelete() throws Exception {
        long t0 = 1700000000000L;
        long tJob = t0 + 10_000L;

        // Unactivated candidate g2 job (FAILED)
        insertUnactivatedCandidateJob("rb-r7-g2", 2L, "FAILED", 5L, tJob, tJob + 5_000L);

        // Article 7001: standalone WITHDRAWN v7 barrier (NO activated projection, NO inbox row),
        // while candidate g2 has PUBLIC v9 and ONLY an ARTICLE_COUNTER v7 APPLIED log!
        jdbcTemplate.update("""
                INSERT INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
                VALUES (7001, 7, 'WITHDRAWN', ?)
                """, tJob + 1_000L);
        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms
                ) VALUES (2, 7001, 1, 9, 'PUBLIC', 7, 700, 2, 'Candidate V9', 'S9', 'B9', ?)
                """, tJob + 2_000L);
        jdbcTemplate.update("""
                INSERT INTO search_rebuild_event_log (
                    candidate_generation, event_id, commit_sequence, event_type,
                    aggregate_type, aggregate_id, source_revision, status, applied_at_ms
                ) VALUES (2, ?, 10, 'ArticleCounterChanged', 'ARTICLE_COUNTER', '7001', 7, 'APPLIED', ?)
                """, UUID.randomUUID().toString(), tJob + 1_000L);

        // Article 7002: standalone DELETED v7 barrier backed by earlier APPLIED ArticleDeleted v7 in inbox,
        // while candidate g2 also holds a DELETED v7 tombstone and ArticleDeleted v7 rebuild log!
        jdbcTemplate.update("""
                INSERT INTO search_event_inbox (
                    event_id, stream_record_id, event_type, schema_version, aggregate_type, aggregate_id,
                    source_revision, visibility, status, received_at_ms, processed_at_ms
                ) VALUES (?, '10-0', 'ArticleDeleted', 2, 'ARTICLE', '7002', 7, 'DELETED', 'APPLIED', ?, ?)
                """, UUID.randomUUID().toString(), t0, t0);
        jdbcTemplate.update("""
                INSERT INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
                VALUES (7002, 7, 'DELETED', ?)
                """, t0);
        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, updated_at_ms
                ) VALUES (2, 7002, 1, 7, 'DELETED', 0, 0, 1, ?)
                """, tJob + 2_000L);
        jdbcTemplate.update("""
                INSERT INTO search_rebuild_event_log (
                    candidate_generation, event_id, commit_sequence, event_type,
                    aggregate_type, aggregate_id, source_revision, status, applied_at_ms
                ) VALUES (2, ?, 11, 'ArticleDeleted', 'ARTICLE', '7002', 7, 'APPLIED', ?)
                """, UUID.randomUUID().toString(), tJob + 2_000L);

        // Deliver late PUBLIC v6 for BOTH 7001 and 7002 on active g1 stream
        String evtLate7001 = UUID.randomUUID().toString();
        String evtLate7002 = UUID.randomUUID().toString();
        redisStub.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                evtLate7001, "ArticleContentChanged", 7001L, 6L, "Zombie7001V6", "S6", "B6", 10L, 1L)));
        redisStub.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                evtLate7002, "ArticleContentChanged", 7002L, 6L, "Zombie7002V6", "S6", "B6", 10L, 1L)));

        streamConsumer.pollOnce();
        indexWorker.processOnce();

        assertThat(consumerRepository.findInboxStatus(evtLate7001)).contains("STALE_IGNORED");
        assertThat(consumerRepository.findInboxStatus(evtLate7002)).contains("STALE_IGNORED");

        assertThat(consumerRepository.findVersionBarrier(7001L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(7L, ArticleVisibility.WITHDRAWN);
        assertThat(consumerRepository.findVersionBarrier(7002L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(7L, ArticleVisibility.DELETED);

        assertThat(consumerRepository.findIndexTask(1L, 7001L)).isEmpty();
        assertThat(consumerRepository.findIndexTask(1L, 7002L)).isEmpty();
        assertThat(esStub.getWrittenDocument(G1, "7001")).isNull();
        assertThat(esStub.getWrittenDocument(G1, "7002")).isNull();
    }

    /**
     * Acceptance Criterion 2 (PUBLIC recovery):
     * Active g1 is at PUBLIC v1; legacy pre-R6 polluted barrier caused v2 event to be XACKed as
     * STALE_IGNORED in search_event_inbox. Covers BOTH post-SQL (barrier repaired to v1) AND
     * pre-SQL (proven candidate-polluted barrier still at v2 -> atomically healed during recovery).
     * Verifies:
     * - Same-eventId Stream redelivery remains DUPLICATE_SKIPPED.
     * - Audited SEARCH-OPS single retry recovers from authoritative Content v2 (even when original
     *   event is no longer fetchable), advances projection & durable index task, worker updates ES g1,
     *   and public search hits v2.
     */
    @Test
    void recoversLegacyMisAckedStaleIgnoredArticleToAuthoritativePublicV2AndServesSearch() throws Exception {
        long t0 = System.currentTimeMillis() - 30_000L;
        long tJob = t0 + 5_000L;
        long tPollute = tJob + 1_000L;

        // Seed active g1 PUBLIC v1 for 7101 (post-SQL barrier=1) and 7102 (pre-SQL proven polluted barrier=2)
        seedActivePublicV1(7101L, "LegacyTitle7101V1", t0);
        seedActivePublicV1(7102L, "LegacyTitle7102V1", t0);

        // Proven candidate g2 snapshot pollution for 7102 (barrier still 2, matching g2 snapshot updated_at_ms)
        insertUnactivatedCandidateJob("rb-r7-pub-g2", 2L, "FAILED", 2L, tJob, tPollute + 1_000L);
        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms
                ) VALUES (2, 7102, 1, 2, 'PUBLIC', 2, 22, 1, 'Recovered7102V2', 'S2', 'B2', ?)
                """, tPollute);
        jdbcTemplate.update("""
                UPDATE search_article_version_barrier
                SET max_content_revision = 2, last_visibility = 'PUBLIC', updated_at_ms = ?
                WHERE article_id = 7102
                """, tPollute);

        // Legacy mis-ACKed STALE_IGNORED inbox rows (v2 content events)
        String evt7101V2 = UUID.randomUUID().toString();
        String evt7102V2 = UUID.randomUUID().toString();
        insertArticleInbox(evt7101V2, "ArticleContentChanged", 7101L, 2L, "PUBLIC", "STALE_IGNORED", tPollute + 500L);
        insertArticleInbox(evt7102V2, "ArticleContentChanged", 7102L, 2L, "PUBLIC", "STALE_IGNORED", tPollute + 500L);

        // Redelivering the same eventId on Stream MUST still be deduplicated (not bypassed)
        redisStub.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                evt7101V2, "ArticleContentChanged", 7101L, 2L, "Redelivered7101V2", "S2", "B2", 20L, 2L)));
        streamConsumer.pollOnce();
        assertThat(consumerRepository.findProjection(1L, 7101L).orElseThrow().contentRevision()).isEqualTo(1L);

        // Authoritative Content holds PUBLIC v2 for 7101 and 7102 (original event need not exist in Stream)
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                7101L, 2L, "AuthoritativeRecovered7101V2", "Recovered Summary 7101", "Recovered Body 7101 V2",
                10L, "Tech", List.of("R7"), 25L, 2L, "2026-11-28T01:00:00Z"));
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                7102L, 2L, "AuthoritativeRecovered7102V2", "Recovered Summary 7102", "Recovered Body 7102 V2",
                10L, "Tech", List.of("R7"), 30L, 2L, "2026-11-28T01:00:00Z"));

        String opsToken = serviceJwtSigner.signServiceToken(
                "knode-content", "knode-search", List.of("ops:read", "ops:retry"), 120);
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + opsToken,
                "Content-Type", "application/json"
        );

        // Operator can discover STALE_IGNORED events via GET /internal/v1/ops/consumers/events?status=STALE_IGNORED
        HttpResponse<String> listStale = send("GET", "/internal/v1/ops/consumers/events?status=STALE_IGNORED", null,
                Map.of("Authorization", "Bearer " + opsToken));
        assertThat(listStale.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(listStale.body()).path("total").asText()).isEqualTo("2");

        // Recover 7101 & 7102 via audited SEARCH-OPS single event retry
        HttpResponse<String> rec7101 = send("POST", "/internal/v1/ops/consumers/" + evt7101V2 + "/retry",
                "{\"replayRequestId\":\"r7-rec-7101\",\"operator\":\"ops-r7\"}", headers);
        assertThat(rec7101.statusCode()).isEqualTo(200);
        JsonNode rec7101Json = MAPPER.readTree(rec7101.body());
        assertThat(rec7101Json.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(rec7101Json.path("requeued").asText()).isEqualTo("1");
        assertThat(rec7101Json.path("articleId").asText()).isEqualTo("7101");
        assertThat(rec7101Json.path("recoveryAction").asText()).isEqualTo("UPSERT_ENQUEUED");
        assertThat(rec7101Json.path("authoritativeRevision").asText()).isEqualTo("2");

        HttpResponse<String> rec7102 = send("POST", "/internal/v1/ops/consumers/" + evt7102V2 + "/retry",
                "{\"replayRequestId\":\"r7-rec-7102\",\"operator\":\"ops-r7\"}", headers);
        assertThat(rec7102.statusCode()).isEqualTo(200);
        JsonNode rec7102Json = MAPPER.readTree(rec7102.body());
        assertThat(rec7102Json.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(rec7102Json.path("requeued").asText()).isEqualTo("1");
        assertThat(rec7102Json.path("recoveryAction").asText()).isEqualTo("UPSERT_ENQUEUED");

        // Verify local projection, barrier, inbox & durable PENDING index tasks before running worker
        for (long id : List.of(7101L, 7102L)) {
            ArticleProjectionRecord proj = consumerRepository.findProjection(1L, id).orElseThrow();
            assertThat(proj.contentRevision()).isEqualTo(2L);
            assertThat(proj.visibility()).isEqualTo(ArticleVisibility.PUBLIC);
            assertThat(proj.projectionRevision()).isEqualTo(2L);
            assertThat(consumerRepository.findVersionBarrier(id).orElseThrow().maxContentRevision()).isEqualTo(2L);

            SearchIndexTaskRecord task = consumerRepository.findIndexTask(1L, id).orElseThrow();
            assertThat(task.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(task.operation()).isEqualTo(SearchIndexOperation.UPSERT);
            assertThat(task.targetProjectionRevision()).isEqualTo(2L);
        }
        assertThat(consumerRepository.findInboxStatus(evt7101V2)).contains("APPLIED");
        assertThat(consumerRepository.findInboxStatus(evt7102V2)).contains("APPLIED");

        // Run worker -> real ES g1 refreshed to v2 -> public search hits authoritative v2!
        SearchIndexTaskWorker.WorkerCycleSummary work = indexWorker.processOnce();
        assertThat(work.appliedCount()).isEqualTo(2);
        assertThat(work.failedCount()).isZero();

        for (long id : List.of(7101L, 7102L)) {
            String keyword = "AuthoritativeRecovered" + id + "V2";
            HttpResponse<String> searchResp = send("GET",
                    "/api/blog/search?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8), null, Map.of());
            assertThat(searchResp.statusCode()).isEqualTo(200);
            JsonNode data = MAPPER.readTree(searchResp.body()).path("data");
            assertThat(data.path("total").asText()).isEqualTo("1");
            assertThat(data.path("rows").get(0).path("id").asText()).isEqualTo(Long.toString(id));
            assertThat(data.path("rows").get(0).path("title").asText()).isEqualTo(keyword);
        }
    }

    /**
     * Acceptance Criterion 2 (WITHDRAWN / DELETED / MISSING -> tombstone only):
     * When authoritative Content is WITHDRAWN, DELETED, or MISSING during recovery of a legacy
     * STALE_IGNORED event, search writes ONLY a tombstone (searchable=false, zero body fields),
     * updates the barrier, and public search returns 0 hits.
     */
    @Test
    void recoversLegacyStaleIgnoredAsTombstoneOnlyWhenContentWithdrawnDeletedOrMissing() throws Exception {
        long t0 = System.currentTimeMillis() - 30_000L;

        seedActivePublicV1(7201L, "TombstoneTargetWithdrawn7201", t0);
        seedActivePublicV1(7202L, "TombstoneTargetDeleted7202", t0);
        seedActivePublicV1(7203L, "TombstoneTargetMissing7203", t0);

        String evt7201 = UUID.randomUUID().toString();
        String evt7202 = UUID.randomUUID().toString();
        String evt7203 = UUID.randomUUID().toString();
        insertArticleInbox(evt7201, "ArticleVisibilityChanged", 7201L, 2L, "WITHDRAWN", "STALE_IGNORED", t0 + 1_000L);
        insertArticleInbox(evt7202, "ArticleDeleted", 7202L, 2L, "DELETED", "STALE_IGNORED", t0 + 1_000L);
        insertArticleInbox(evt7203, "ArticleContentChanged", 7203L, 2L, "PUBLIC", "STALE_IGNORED", t0 + 1_000L);

        // Authoritative Content: 7201 WITHDRAWN v2, 7202 DELETED v2, 7203 MISSING
        contentStub.putCurrentHiddenArticle(7201L, "WITHDRAWN", 2L);
        contentStub.putCurrentHiddenArticle(7202L, "DELETED", 2L);
        contentStub.removeCurrentArticle(7203L);

        String opsToken = serviceJwtSigner.signServiceToken("knode-content", "knode-search", List.of("ops:retry"), 120);
        Map<String, String> headers = Map.of("Authorization", "Bearer " + opsToken, "Content-Type", "application/json");

        for (Map.Entry<String, String> entry : Map.of(
                evt7201, "r7-tomb-7201",
                evt7202, "r7-tomb-7202",
                evt7203, "r7-tomb-7203"
        ).entrySet()) {
            HttpResponse<String> resp = send("POST", "/internal/v1/ops/consumers/" + entry.getKey() + "/retry",
                    "{\"replayRequestId\":\"" + entry.getValue() + "\",\"operator\":\"ops-r7\"}", headers);
            assertThat(resp.statusCode()).isEqualTo(200);
            JsonNode body = MAPPER.readTree(resp.body());
            assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");
            assertThat(body.path("requeued").asText()).isEqualTo("1");
            assertThat(body.path("recoveryAction").asText()).isEqualTo("TOMBSTONE_ENQUEUED");
        }

        SearchIndexTaskWorker.WorkerCycleSummary work = indexWorker.processOnce();
        assertThat(work.appliedCount()).isEqualTo(3);

        for (long id : List.of(7201L, 7202L, 7203L)) {
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

        assertThat(consumerRepository.findVersionBarrier(7201L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(2L, ArticleVisibility.WITHDRAWN);
        assertThat(consumerRepository.findVersionBarrier(7202L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(2L, ArticleVisibility.DELETED);
        assertThat(consumerRepository.findVersionBarrier(7203L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(2L, ArticleVisibility.DELETED);

        for (String kw : List.of("TombstoneTargetWithdrawn7201", "TombstoneTargetDeleted7202", "TombstoneTargetMissing7203")) {
            HttpResponse<String> s = send("GET", "/api/blog/search?keyword=" + kw, null, Map.of());
            assertThat(MAPPER.readTree(s.body()).path("data").path("total").asText()).isEqualTo("0");
        }
    }

    /**
     * Acceptance Criterion 2 (Idempotency, Concurrency, No-Regression, Normal STALE_IGNORED, Failures):
     * - Same replayRequestId repeated & concurrent -> single execution, single audit row, exact response.
     * - Different replayRequestIds concurrently targeting same STALE_IGNORED event -> serialized,
     *   one ACCEPTED + one NOOP, projection_revision bumped ONCE (never twice!).
     * - Post-recovery call with new replayRequestId -> NOOP, never regresses even if Content lags.
     * - Normal STALE_IGNORED events (late v1, barrier-blocked v6, stale counter) -> NOOP, 0 Content calls.
     * - Upstream 503 / cutover pause / active lease / param conflict -> clear locatable error & 0 mutation.
     */
    @Test
    void enforcesIdempotencyConcurrencyNoRegressionNormalStaleNoopAndLocatableFailures() throws Exception {
        long t0 = System.currentTimeMillis() - 30_000L;
        String opsToken = serviceJwtSigner.signServiceToken("knode-content", "knode-search", List.of("ops:retry"), 120);
        Map<String, String> headers = Map.of("Authorization", "Bearer " + opsToken, "Content-Type", "application/json");

        // --- A. Concurrent SAME replayRequestId on 7301 ---
        seedActivePublicV1(7301L, "ConcSameKey7301V1", t0);
        String evt7301 = UUID.randomUUID().toString();
        insertArticleInbox(evt7301, "ArticleContentChanged", 7301L, 2L, "PUBLIC", "STALE_IGNORED", t0 + 1_000L);
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                7301L, 2L, "ConcSameKey7301V2", "S2", "B2", 10L, "Tech", List.of("R7"), 11L, 2L, "2026-11-28T01:00:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readySame = new CountDownLatch(2);
            CountDownLatch goSame = new CountDownLatch(1);
            String sameBody = "{\"replayRequestId\":\"r7-conc-same\",\"operator\":\"ops-r7\"}";
            Future<HttpResponse<String>> f1 = pool.submit(() -> {
                readySame.countDown();
                goSame.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/" + evt7301 + "/retry", sameBody, headers);
            });
            Future<HttpResponse<String>> f2 = pool.submit(() -> {
                readySame.countDown();
                goSame.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/" + evt7301 + "/retry", sameBody, headers);
            });
            assertThat(readySame.await(5, TimeUnit.SECONDS)).isTrue();
            goSame.countDown();
            HttpResponse<String> r1 = f1.get(10, TimeUnit.SECONDS);
            HttpResponse<String> r2 = f2.get(10, TimeUnit.SECONDS);
            assertThat(r1.statusCode()).isEqualTo(200);
            assertThat(r2.statusCode()).isEqualTo(200);
            assertThat(r1.body()).isEqualTo(r2.body());
            assertThat(MAPPER.readTree(r1.body()).path("status").asText()).isEqualTo("ACCEPTED");
            assertThat(consumerRepository.findProjection(1L, 7301L).orElseThrow().projectionRevision()).isEqualTo(2L);
            assertThat(consumerRepository.findIndexTask(1L, 7301L).orElseThrow().targetProjectionRevision()).isEqualTo(2L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = 'r7-conc-same'", Long.class))
                    .isEqualTo(1L);

            // Different params with same replayRequestId -> 409 IDEMPOTENCY_CONFLICT
            HttpResponse<String> conflict = send("POST", "/internal/v1/ops/consumers/" + evt7301 + "/retry",
                    "{\"replayRequestId\":\"r7-conc-same\",\"operator\":\"other-op\"}", headers);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(MAPPER.readTree(conflict.body()).path("error").path("code").asText())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");

            // --- B. Concurrent DIFFERENT replayRequestIds targeting same STALE_IGNORED event 7302 ---
            seedActivePublicV1(7302L, "ConcDiffKey7302V1", t0);
            String evt7302 = UUID.randomUUID().toString();
            insertArticleInbox(evt7302, "ArticleContentChanged", 7302L, 2L, "PUBLIC", "STALE_IGNORED", t0 + 1_000L);
            contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                    7302L, 2L, "ConcDiffKey7302V2", "S2", "B2", 10L, "Tech", List.of("R7"), 12L, 2L, "2026-11-28T01:00:00Z"));

            CountDownLatch readyDiff = new CountDownLatch(2);
            CountDownLatch goDiff = new CountDownLatch(1);
            Future<HttpResponse<String>> d1 = pool.submit(() -> {
                readyDiff.countDown();
                goDiff.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/" + evt7302 + "/retry",
                        "{\"replayRequestId\":\"r7-conc-diff-A\",\"operator\":\"ops-r7\"}", headers);
            });
            Future<HttpResponse<String>> d2 = pool.submit(() -> {
                readyDiff.countDown();
                goDiff.await(5, TimeUnit.SECONDS);
                return send("POST", "/internal/v1/ops/consumers/" + evt7302 + "/retry",
                        "{\"replayRequestId\":\"r7-conc-diff-B\",\"operator\":\"ops-r7\"}", headers);
            });
            assertThat(readyDiff.await(5, TimeUnit.SECONDS)).isTrue();
            goDiff.countDown();
            HttpResponse<String> dr1 = d1.get(10, TimeUnit.SECONDS);
            HttpResponse<String> dr2 = d2.get(10, TimeUnit.SECONDS);
            assertThat(dr1.statusCode()).isEqualTo(200);
            assertThat(dr2.statusCode()).isEqualTo(200);
            Set<String> statuses = Set.of(
                    MAPPER.readTree(dr1.body()).path("status").asText(),
                    MAPPER.readTree(dr2.body()).path("status").asText()
            );
            assertThat(statuses).containsExactlyInAnyOrder("ACCEPTED", "NOOP");
            // Projection revision bumped ONCE (1 -> 2), NEVER twice!
            assertThat(consumerRepository.findProjection(1L, 7302L).orElseThrow().projectionRevision()).isEqualTo(2L);
            assertThat(consumerRepository.findIndexTask(1L, 7302L).orElseThrow().targetProjectionRevision()).isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }

        // --- C. After worker completes 7301 (DONE at v2), new replayRequestId -> NOOP & no regression ---
        indexWorker.processOnce();
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                7301L, 1L, "StaleRegressedV1", "S1", "B1", 10L, "Tech", List.of(), 1L, 1L, "2026-11-28T01:00:00Z"));
        HttpResponse<String> afterDone = send("POST", "/internal/v1/ops/consumers/" + evt7301 + "/retry",
                "{\"replayRequestId\":\"r7-after-done\",\"operator\":\"ops-r7\"}", headers);
        assertThat(afterDone.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(afterDone.body()).path("status").asText()).isEqualTo("NOOP");
        assertThat(consumerRepository.findProjection(1L, 7301L).orElseThrow().contentRevision()).isEqualTo(2L);
        assertThat(consumerRepository.findProjection(1L, 7301L).orElseThrow().title()).isEqualTo("ConcSameKey7301V2");

        // --- D. Normal STALE_IGNORED events remain strictly NOOP with ZERO Content calls ---
        String evtNormalLate = UUID.randomUUID().toString();
        insertArticleInbox(evtNormalLate, "ArticleContentChanged", 7301L, 1L, "PUBLIC", "STALE_IGNORED", t0 + 2_000L);

        // Valid standalone WITHDRAWN v7 barrier blocking v6 STALE_IGNORED
        jdbcTemplate.update("""
                INSERT INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
                VALUES (7303, 7, 'WITHDRAWN', ?)
                """, t0);
        String evtBarrierBlocked = UUID.randomUUID().toString();
        insertArticleInbox(evtBarrierBlocked, "ArticleContentChanged", 7303L, 6L, "PUBLIC", "STALE_IGNORED", t0 + 2_000L);

        // Stale ARTICLE_COUNTER STALE_IGNORED
        String evtStaleCounter = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO search_event_inbox (
                    event_id, stream_record_id, event_type, schema_version, aggregate_type, aggregate_id,
                    source_revision, visibility, status, received_at_ms, processed_at_ms
                ) VALUES (?, '99-0', 'ArticleCounterChanged', 1, 'ARTICLE_COUNTER', '7301', 99, 'PUBLIC', 'STALE_IGNORED', ?, ?)
                """, evtStaleCounter, t0 + 2_000L, t0 + 2_000L);

        contentStub.clearRequests();
        for (Map.Entry<String, String> e : Map.of(
                evtNormalLate, "r7-normal-late",
                evtBarrierBlocked, "r7-barrier-blocked",
                evtStaleCounter, "r7-stale-counter"
        ).entrySet()) {
            HttpResponse<String> resp = send("POST", "/internal/v1/ops/consumers/" + e.getKey() + "/retry",
                    "{\"replayRequestId\":\"" + e.getValue() + "\",\"operator\":\"ops-r7\"}", headers);
            assertThat(resp.statusCode()).isEqualTo(200);
            JsonNode j = MAPPER.readTree(resp.body());
            assertThat(j.path("status").asText()).isEqualTo("NOOP");
            assertThat(j.path("requeued").asText()).isEqualTo("0");
            assertThat(j.path("reason").asText()).isEqualTo("EVENT_STALE_IGNORED");
        }
        assertThat(contentStub.requests()).isEmpty();
        assertThat(consumerRepository.findIndexTask(1L, 7303L)).isEmpty();

        // --- E. Locatable failure states (503 Content outage -> retryable; 409 cutover; 409 active lease) ---
        seedActivePublicV1(7304L, "FailureState7304V1", t0);
        String evt7304 = UUID.randomUUID().toString();
        insertArticleInbox(evt7304, "ArticleContentChanged", 7304L, 2L, "PUBLIC", "STALE_IGNORED", t0 + 1_000L);
        contentStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                7304L, 2L, "FailureState7304V2", "S2", "B2", 10L, "Tech", List.of("R7"), 5L, 2L, "2026-11-28T01:00:00Z"));

        // E1: Upstream 503 -> HTTP 503 DEPENDENCY_UNAVAILABLE (retryable=true), 0 audit, 0 mutation
        contentStub.setInterceptor((req, def) ->
                LoopbackContentSnapshotStub.HttpStubResponse.status(503, "{\"error\":{\"code\":\"UNAVAILABLE\"}}"));
        try {
            HttpResponse<String> outage = send("POST", "/internal/v1/ops/consumers/" + evt7304 + "/retry",
                    "{\"replayRequestId\":\"r7-fail-7304\",\"operator\":\"ops-r7\"}", headers);
            assertThat(outage.statusCode()).isEqualTo(503);
            JsonNode err = MAPPER.readTree(outage.body()).path("error");
            assertThat(err.path("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
            assertThat(err.path("retryable").asBoolean()).isTrue();
            assertThat(consumerRepository.findProjection(1L, 7304L).orElseThrow().contentRevision()).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = 'r7-fail-7304'", Long.class))
                    .isZero();
        } finally {
            contentStub.clearInterceptor();
        }

        // E2: Cutover paused -> HTTP 409 STATE_CONFLICT (retryable=true), 0 audit, 0 mutation
        jdbcTemplate.update("UPDATE search_index_generation SET cutover_state = 'CUTOVER_PAUSED' WHERE alias_name = ?", ES_ALIAS);
        try {
            HttpResponse<String> cutover = send("POST", "/internal/v1/ops/consumers/" + evt7304 + "/retry",
                    "{\"replayRequestId\":\"r7-fail-7304\",\"operator\":\"ops-r7\"}", headers);
            assertThat(cutover.statusCode()).isEqualTo(409);
            assertThat(MAPPER.readTree(cutover.body()).path("error").path("code").asText()).isEqualTo("STATE_CONFLICT");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = 'r7-fail-7304'", Long.class))
                    .isZero();
        } finally {
            jdbcTemplate.update("UPDATE search_index_generation SET cutover_state = 'NORMAL' WHERE alias_name = ?", ES_ALIAS);
        }

        // E3: Active unexpired worker lease -> HTTP 409 STATE_CONFLICT (retryable=true), 0 audit, 0 mutation
        jdbcTemplate.update("""
                UPDATE search_index_task
                SET status = 'LEASED', lease_until_ms = ?
                WHERE index_generation = 1 AND article_id = 7304
                """, System.currentTimeMillis() + 120_000L);
        HttpResponse<String> leased = send("POST", "/internal/v1/ops/consumers/" + evt7304 + "/retry",
                "{\"replayRequestId\":\"r7-fail-7304\",\"operator\":\"ops-r7\"}", headers);
        assertThat(leased.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(leased.body()).path("error").path("code").asText()).isEqualTo("STATE_CONFLICT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = 'r7-fail-7304'", Long.class))
                .isZero();

        // E4: Once lease finishes, retrying the SAME replayRequestId succeeds cleanly!
        jdbcTemplate.update("""
                UPDATE search_index_task
                SET status = 'DONE', lease_until_ms = NULL
                WHERE index_generation = 1 AND article_id = 7304
                """);
        HttpResponse<String> recovered = send("POST", "/internal/v1/ops/consumers/" + evt7304 + "/retry",
                "{\"replayRequestId\":\"r7-fail-7304\",\"operator\":\"ops-r7\"}", headers);
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(recovered.body()).path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(consumerRepository.findProjection(1L, 7304L).orElseThrow().contentRevision()).isEqualTo(2L);
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
        tag.put("name", "R7");
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
                    return "r7-content-token";
                }

                @Override
                public void invalidate(String audience, List<String> permissions) {
                }
            };
        }
    }
}
