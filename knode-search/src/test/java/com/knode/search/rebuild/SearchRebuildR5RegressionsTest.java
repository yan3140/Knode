package com.knode.search.rebuild;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.cache.ArticleSearchCache;
import com.knode.search.cache.SearchCacheInvalidator;
import com.knode.search.client.ContentVisibilityClient;
import com.knode.search.client.ServiceTokenClient;
import com.knode.search.client.VisibilityDecision;
import com.knode.search.config.SearchCacheProperties;
import com.knode.search.config.SearchClientsProperties;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.config.SearchQueryProperties;
import com.knode.search.config.SearchRuntimeConfig;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.event.ContentEventParser;
import com.knode.search.consumer.indexer.ElasticsearchArticleIndexWriter;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexDocument;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.PageResult;
import com.knode.search.model.QueryBudget;
import com.knode.search.rebuild.client.HttpContentSnapshotClient;
import com.knode.search.rebuild.config.SearchRebuildProperties;
import com.knode.search.rebuild.es.ElasticsearchIndexAdminClient;
import com.knode.search.rebuild.persistence.RebuildJobRecord;
import com.knode.search.rebuild.persistence.RebuildPhase;
import com.knode.search.rebuild.persistence.SearchRebuildRepository;
import com.knode.search.rebuild.service.SearchRebuildService;
import com.knode.search.repository.ElasticsearchArticleSearchRepository;
import com.knode.search.service.PublicArticleSearchService;
import com.knode.search.service.SearchQueryPlanner;
import com.knode.search.support.LoopbackContentSnapshotStub;
import com.knode.search.support.LoopbackRedisStub;
import com.knode.search.support.StatefulElasticsearchStub;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRebuildR5RegressionsTest {
    private static final String ENV = "it-rebuild-r5";
    private static final String ALIAS = "knode_it_r5_articles_search";
    private static final String G1 = ALIAS + "_g1";
    private static final String G2 = ALIAS + "_g2";
    private static final String G3 = ALIAS + "_g3";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MutableClock clock;
    private LoopbackRedisStub cacheRedis;
    private LettuceConnectionFactory cacheRedisFactory;
    private StringRedisTemplate cacheRedisTemplate;
    private StatefulElasticsearchStub esStub;
    private ElasticsearchClient esClient;
    private LoopbackContentSnapshotStub snapshotStub;

    private SearchElasticsearchProperties esProps;
    private SearchQueryProperties queryProps;
    private SearchCacheProperties cacheProps;
    private SearchConsumerProperties consumerProps;
    private SearchRebuildProperties rebuildProps;
    private ContentEventParser eventParser;
    private ElasticsearchArticleIndexWriter indexWriter;
    private SearchCacheInvalidator cacheInvalidator;
    private HttpContentSnapshotClient snapshotClient;
    private ElasticsearchIndexAdminClient indexAdminClient;
    private ContentVisibilityClient visibilityClient;
    private Map<String, VisibilityDecision> visibilityTable;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-11-26T15:00:00Z"));
        cacheRedis = new LoopbackRedisStub();
        RedisStandaloneConfiguration cacheConf = new RedisStandaloneConfiguration("127.0.0.1", cacheRedis.port());
        LettuceClientConfiguration cacheClientConf = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(800))
                .build();
        cacheRedisFactory = new LettuceConnectionFactory(cacheConf, cacheClientConf);
        cacheRedisFactory.afterPropertiesSet();
        cacheRedisTemplate = new StringRedisTemplate(cacheRedisFactory);

        esStub = new StatefulElasticsearchStub();
        esStub.setAlias(ALIAS, G1);

        esProps = new SearchElasticsearchProperties();
        esProps.setUrl(esStub.baseUrl());
        esProps.setIndex(ALIAS);
        esProps.setConnectTimeoutMs(400);
        esProps.setConnectionRequestTimeoutMs(400);
        esProps.setResponseTimeoutMs(700);

        queryProps = new SearchQueryProperties();
        queryProps.setTotalTimeoutMs(2500);

        SearchRuntimeConfig runtimeConfig = new SearchRuntimeConfig();
        esClient = runtimeConfig.elasticsearchClient(esProps, queryProps);

        snapshotStub = new LoopbackContentSnapshotStub();
        snapshotStub.setExpectedBearerToken("rb-r5-token");

        consumerProps = new SearchConsumerProperties();
        consumerProps.setEnabled(true);
        consumerProps.getIndexer().setEnabled(true);
        consumerProps.getIndexer().setBatchSize(20);
        consumerProps.getIndexer().setLeaseSeconds(10);
        consumerProps.getIndexer().setMaxAttempts(3);
        consumerProps.getIndexer().setBaseRetryDelayMs(500L);
        consumerProps.getIndexer().setMaxRetryDelayMs(5000L);
        consumerProps.getIndexer().setRefreshPolicy("wait_for");
        consumerProps.getIndexer().setCacheCompensationBatchSize(10);
        consumerProps.getIndexer().setCacheCompensationBaseDelayMs(0L);
        consumerProps.getIndexer().setCacheCompensationMaxDelayMs(0L);

        rebuildProps = new SearchRebuildProperties();
        rebuildProps.setEnabled(true);
        rebuildProps.setInstanceId("rb-r5-inst-1");
        rebuildProps.setSnapshotPageSize(2);
        rebuildProps.setChangeBatchLimit(10);
        rebuildProps.setRequestTimeoutMs(5000L);
        rebuildProps.setJobLeaseSeconds(10);
        rebuildProps.setVerificationSampleSize(20);
        rebuildProps.setMaxSwitchAttempts(3);

        cacheProps = new SearchCacheProperties();
        cacheProps.setEnabled(true);
        cacheProps.setAdmissionThreshold(2);
        cacheProps.setAdmissionTtlSeconds(60);
        cacheProps.setResultTtlSeconds(60);
        cacheProps.setEmptyTtlSeconds(30);
        cacheProps.setJitterSeconds(0);

        SearchClientsProperties clientsProps = new SearchClientsProperties();
        clientsProps.setContentOrigin(snapshotStub.baseUrl());
        clientsProps.setContentSnapshotTimeoutMs(5000L);

        visibilityTable = new ConcurrentHashMap<>();
        visibilityClient = (ids, budget, requestId) -> {
            Map<String, VisibilityDecision> out = new LinkedHashMap<>();
            for (String id : ids) {
                out.put(id, visibilityTable.getOrDefault(id, new VisibilityDecision(id, null, "MISSING", "DENIED")));
            }
            return out;
        };

        ServiceTokenClient tokenClient = new ServiceTokenClient() {
            @Override
            public String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
                return "rb-r5-token";
            }

            @Override
            public void invalidate(String audience, List<String> permissions) {
            }
        };

        eventParser = new ContentEventParser();
        ObjectProvider<StringRedisTemplate> cacheProvider = singleObjectProvider(cacheRedisTemplate);
        cacheInvalidator = new SearchCacheInvalidator(cacheProvider, ENV);
        indexWriter = new ElasticsearchArticleIndexWriter(esClient, esProps, consumerProps, MAPPER);
        snapshotClient = new HttpContentSnapshotClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clientsProps,
                tokenClient
        );
        indexAdminClient = new ElasticsearchIndexAdminClient(esClient, MAPPER);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (cacheRedisFactory != null) {
            cacheRedisFactory.destroy();
        }
        if (esClient != null) {
            esClient.close();
        }
        if (snapshotStub != null) {
            snapshotStub.close();
        }
        if (esStub != null) {
            esStub.close();
        }
        if (cacheRedis != null) {
            cacheRedis.close();
        }
    }

    /**
     * SEARCH-03-R5 Regression 1:
     * R2 already switched ES public alias uniquely to g2, stopped BEFORE writing local SQL activation,
     * leaving SQL job in SWITCHING_ALIAS, search_index_generation at active_generation = 1 / cutover_state = SWITCHING_ALIAS,
     * and Redis cutover_bypass holding legacy "<alias>:g2" (no watermark).
     * After upgrading via db/search-rebuild-03-r3-upgrade.sql:
     * 1) Upgraded instance reconciles forward from SWITCHING_ALIAS with Redis INCR failing:
     *    - ES alias uniquely points to g2, DB active_generation becomes 2, cutover_state returns to NORMAL,
     *    - Legacy "<alias>:g2" is safely taken over as "<alias>:g2:a1" (watermark "2:1:ACTIVE") and remains active
     *      while compensation stays PENDING,
     *    - Zero-DB public search bypasses stale :e0 empty cache and hits g2,
     *    - Stale legacy "<alias>:g2" clear cannot delete the active attempt bypass.
     * 2) Once Redis INCR recovers, rebuild recovery advances epoch to 1, clears cutover_bypass completely
     *    (watermark "2:1:CLEARED"), and completes both job (COMPLETED) and generation compensation (DONE).
     * 3) Warming the same query on epoch :e1 then hits Redis cache with ES search delta == 0.
     */
    @Test
    void reconcilesR2SwitchedAliasAndClearsLegacyCutoverBypassViaRebuildRecovery() throws Exception {
        // 1) Warm stale empty-result cache on G1 (epoch 0) before R2 switched alias to G2.
        PublicArticleSearchService publicSearch = createPublicSearchService();
        assertThat(publicSearch.search("R2SwitchG2Token", 1, 5, "relevance", "trace-r5-warm-1").rows()).isEmpty();
        assertThat(publicSearch.search("R2SwitchG2Token", 1, 5, "relevance", "trace-r5-warm-2").rows()).isEmpty();
        int esBeforeWarmHit = countEsSearchRequests();
        assertThat(publicSearch.search("R2SwitchG2Token", 1, 5, "relevance", "trace-r5-warm-hit").rows()).isEmpty();
        assertThat(countEsSearchRequests()).isEqualTo(esBeforeWarmHit);

        // 2) Restore upgraded R2 state: ES alias uniquely -> {G2}, SQL job in SWITCHING_ALIAS / active_generation=1,
        //    Redis holds legacy "<alias>:g2" without watermark.
        try (HikariDataSource ds = createR2UpgradedSwitchingDatabase("rb-r2-switching-1")) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            SearchConsumerRepository consumerRepo = new SearchConsumerRepository(jdbc, MAPPER);
            SearchRebuildRepository rebuildRepo = new SearchRebuildRepository(jdbc, consumerRepo, MAPPER);
            SearchIndexTaskWorker worker = new SearchIndexTaskWorker(
                    consumerRepo, tx, indexWriter, cacheInvalidator, consumerProps, ALIAS, clock);
            SearchRebuildService rebuildService = new SearchRebuildService(
                    rebuildRepo, consumerRepo, tx, snapshotClient, eventParser, worker,
                    indexAdminClient, cacheInvalidator, consumerProps, rebuildProps, ALIAS,
                    "inst-r5-recovery-1", MAPPER, clock);

            indexDocDirectly(G2, 9501L, 2L, "R2SwitchG2Token Live In G2");
            visibilityTable.put("9501", new VisibilityDecision("9501", "2", "PUBLIC", "READABLE"));
            esStub.setAlias(ALIAS, G2);

            String legacyOwner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L);
            String expectedAdoptedOwner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 1L);
            cacheRedis.putRaw(cacheInvalidator.cutoverBypassKey(), legacyOwner);
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassWatermarkKey())).isFalse();

            // Verify pre-recovery upgraded state in SQL.
            IndexGenerationState preGen = consumerRepo.findGenerationState(ALIAS).orElseThrow();
            assertThat(preGen.activeGeneration()).isEqualTo(1L);
            assertThat(preGen.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);
            assertThat(preGen.activeCutoverBypassOwner()).isNull();
            assertThat(rebuildRepo.findJob("rb-r2-switching-1").orElseThrow().phase())
                    .isEqualTo(RebuildPhase.SWITCHING_ALIAS);

            // 3) Inject Redis INCR failure during cache invalidation phase, then reconcile from SWITCHING_ALIAS!
            cacheRedis.failCommand("INCR", "ERR redis incr down during R2 switch recovery");
            SearchRebuildService.StepOutcome pendingOut = rebuildService.advanceJob("rb-r2-switching-1");
            assertThat(pendingOut.status()).isEqualTo(SearchRebuildService.StepStatus.CACHE_INVALIDATION_PENDING);
            assertThat(pendingOut.job().phase()).isEqualTo(RebuildPhase.CACHE_INVALIDATING);

            // Assert ES alias uniquely on G2, DB active_generation = 2, cutover_state = NORMAL, compensation = PENDING.
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
            IndexGenerationState activatedGen = consumerRepo.findGenerationState(ALIAS).orElseThrow();
            assertThat(activatedGen.activeGeneration()).isEqualTo(2L);
            assertThat(activatedGen.activePhysicalIndex()).isEqualTo(G2);
            assertThat(activatedGen.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);
            assertThat(activatedGen.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);
            assertThat(activatedGen.activeCutoverBypassOwner()).isEqualTo(expectedAdoptedOwner);

            // Legacy "<alias>:g2" was safely taken over as "<alias>:g2:a1" (watermark "2:1:ACTIVE") and remains active.
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedAdoptedOwner);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:ACTIVE");

            // Stale legacy owner cleanup cannot remove the active adopted bypass marker.
            assertThat(cacheInvalidator.clearCutoverBypassIfOwned(legacyOwner)).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedAdoptedOwner);

            // Public search (zero consumer DB) continues bypassing stale :e0 empty cache and hits active G2!
            PageResult duringIncrFailure = publicSearch.search(
                    "R2SwitchG2Token", 1, 5, "relevance", "trace-r5-bypass-hit");
            assertThat(duringIncrFailure.rows()).hasSize(1);
            assertThat(duringIncrFailure.rows().get(0).id()).isEqualTo(9501L);

            // 4) Restore Redis INCR -> normal rebuild recovery advances epoch, clears bypass, and completes job & compensation.
            cacheRedis.clearFaults();
            SearchRebuildService.StepOutcome completedOut = rebuildService.advanceJob("rb-r2-switching-1");
            assertThat(completedOut.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
            assertThat(completedOut.job().phase()).isEqualTo(RebuildPhase.COMPLETED);

            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:CLEARED");

            IndexGenerationState doneGen = consumerRepo.findGenerationState(ALIAS).orElseThrow();
            assertThat(doneGen.activeGeneration()).isEqualTo(2L);
            assertThat(doneGen.activePhysicalIndex()).isEqualTo(G2);
            assertThat(doneGen.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);
            assertThat(doneGen.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
            assertThat(doneGen.activeCutoverBypassOwner()).isNull();
            assertThat(worker.processPendingCacheInvalidations().candidateCount()).isZero();

            // 5) Warm same query on new epoch :e1, then assert cache hit with ES query delta == 0!
            PublicArticleSearchService freshPublicSearch = createPublicSearchService();
            PageResult e1First = freshPublicSearch.search(
                    "R2SwitchG2Token", 1, 5, "relevance", "trace-r5-e1-1");
            PageResult e1Second = freshPublicSearch.search(
                    "R2SwitchG2Token", 1, 5, "relevance", "trace-r5-e1-2");
            assertThat(e1First.rows()).hasSize(1);
            assertThat(e1Second.rows()).hasSize(1);

            int esBeforeCacheHit = countEsSearchRequests();
            PageResult e1Cached = freshPublicSearch.search(
                    "R2SwitchG2Token", 1, 5, "relevance", "trace-r5-e1-3");
            assertThat(e1Cached.rows()).hasSize(1);
            assertThat(e1Cached.rows().get(0).id()).isEqualTo(9501L);
            assertThat(countEsSearchRequests()).isEqualTo(esBeforeCacheHit);
        }
    }

    /**
     * SEARCH-03-R5 Regression 2:
     * Companion check on the same R2 SWITCHING_ALIAS upgrade path verifying that when background
     * SearchIndexTaskWorker compensates after Redis INCR recovers, the adopted bypass is cleared,
     * rebuild job finishes cleanly without double-incrementing epoch, new-epoch caching hits Redis
     * with ES delta == 0, and a subsequent g3 rebuild continues protecting its own attempt marker.
     */
    @Test
    void reconcilesR2SwitchedAliasAndClearsLegacyCutoverBypassViaBackgroundCompensationWorker() throws Exception {
        PublicArticleSearchService publicSearch = createPublicSearchService();
        assertThat(publicSearch.search("R2WorkerG2Token", 1, 5, "relevance", "trace-r5w-warm-1").rows()).isEmpty();
        assertThat(publicSearch.search("R2WorkerG2Token", 1, 5, "relevance", "trace-r5w-warm-2").rows()).isEmpty();

        try (HikariDataSource ds = createR2UpgradedSwitchingDatabase("rb-r2-switching-2")) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            SearchConsumerRepository consumerRepo = new SearchConsumerRepository(jdbc, MAPPER);
            SearchRebuildRepository rebuildRepo = new SearchRebuildRepository(jdbc, consumerRepo, MAPPER);
            SearchIndexTaskWorker worker = new SearchIndexTaskWorker(
                    consumerRepo, tx, indexWriter, cacheInvalidator, consumerProps, ALIAS, clock);
            SearchRebuildService rebuildService = new SearchRebuildService(
                    rebuildRepo, consumerRepo, tx, snapshotClient, eventParser, worker,
                    indexAdminClient, cacheInvalidator, consumerProps, rebuildProps, ALIAS,
                    "inst-r5-recovery-2", MAPPER, clock);

            indexDocDirectly(G2, 9502L, 2L, "R2WorkerG2Token Live In G2");
            visibilityTable.put("9502", new VisibilityDecision("9502", "2", "PUBLIC", "READABLE"));
            esStub.setAlias(ALIAS, G2);
            cacheRedis.putRaw(cacheInvalidator.cutoverBypassKey(), SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L));

            // 1) Reconcile from SWITCHING_ALIAS while Redis INCR fails -> activates G2 & adopts bypass as g2:a1.
            cacheRedis.failCommand("INCR", "ERR redis incr down before worker compensation");
            SearchRebuildService.StepOutcome pendingOut = rebuildService.advanceJob("rb-r2-switching-2");
            assertThat(pendingOut.status()).isEqualTo(SearchRebuildService.StepStatus.CACHE_INVALIDATION_PENDING);
            assertThat(consumerRepo.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(2L);
            assertThat(consumerRepo.findGenerationState(ALIAS).orElseThrow().cutoverState())
                    .isEqualTo(IndexGenerationState.CutoverState.NORMAL);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey()))
                    .isEqualTo(SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 1L));

            // Public query still bypasses stale :e0 empty cache while PENDING.
            PageResult pendingHit = publicSearch.search(
                    "R2WorkerG2Token", 1, 5, "relevance", "trace-r5w-pending");
            assertThat(pendingHit.rows()).hasSize(1);
            assertThat(pendingHit.rows().get(0).id()).isEqualTo(9502L);

            // 2) Restore Redis INCR -> background worker compensates FIRST, clears bypass, advances epoch to 1.
            cacheRedis.clearFaults();
            clock.advance(Duration.ofSeconds(2));
            SearchIndexTaskWorker.CacheCompensationSummary comp = worker.processPendingCacheInvalidations();
            assertThat(comp.completedCount()).isEqualTo(1);
            assertThat(comp.failedCount()).isZero();
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:CLEARED");

            // Rebuild service completes job without double-incrementing epoch.
            SearchRebuildService.StepOutcome completedOut = rebuildService.advanceJob("rb-r2-switching-2");
            assertThat(completedOut.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
            assertThat(completedOut.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();

            // 3) Warm new-epoch :e1 cache and assert cache hit with ES delta == 0.
            PublicArticleSearchService freshPublicSearch = createPublicSearchService();
            assertThat(freshPublicSearch.search("R2WorkerG2Token", 1, 5, "relevance", "trace-r5w-e1-1").rows())
                    .hasSize(1);
            assertThat(freshPublicSearch.search("R2WorkerG2Token", 1, 5, "relevance", "trace-r5w-e1-2").rows())
                    .hasSize(1);
            int esBeforeHit = countEsSearchRequests();
            PageResult cachedHit = freshPublicSearch.search(
                    "R2WorkerG2Token", 1, 5, "relevance", "trace-r5w-e1-3");
            assertThat(cachedHit.rows()).hasSize(1);
            assertThat(cachedHit.rows().get(0).id()).isEqualTo(9502L);
            assertThat(countEsSearchRequests()).isEqualTo(esBeforeHit);

            // 4) Subsequent g3 rebuild succeeds and preserves attempt-scoped bypass ownership against stale g2 cleanup.
            snapshotStub.setLivePublicArticles(List.of(
                    LoopbackContentSnapshotStub.snapshotItem(9502L, 2L, "R2WorkerG2Token Live In G2",
                            "Sum G2", "Body G2", 1L, "Tech", List.of("ES"), 10L, 1L, "2026-10-01T00:00:00Z"),
                    LoopbackContentSnapshotStub.snapshotItem(9503L, 3L, "G3AfterR2SwitchRecoveryArticle",
                            "Sum G3", "Body G3", 1L, "Tech", List.of("ES"), 20L, 1L, "2026-10-02T00:00:00Z")
            ));
            visibilityTable.put("9503", new VisibilityDecision("9503", "3", "PUBLIC", "READABLE"));

            RebuildJobRecord g3Job = rebuildService.startRebuild("req-r5-g3-follow");
            cacheRedis.failCommand("INCR", "ERR redis incr down on g3");
            RebuildJobRecord g3Pending = advanceUntilPhase(rebuildService, g3Job.jobId(), RebuildPhase.CACHE_INVALIDATING);
            String g3Owner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 3L, 1L);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(g3Owner);
            // Stale g2:a1 and legacy g2 cleanups MUST NOT delete active g3:a1 bypass!
            assertThat(cacheInvalidator.clearCutoverBypassIfOwned(
                    SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 1L))).isFalse();
            assertThat(cacheInvalidator.clearCutoverBypassIfOwned(
                    SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L))).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(g3Owner);

            cacheRedis.clearFaults();
            RebuildJobRecord g3Completed = rebuildService.runUntilTerminalOrLimit(g3Pending.jobId(), 5);
            assertThat(g3Completed.phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("3:1:CLEARED");
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("2");
        }
    }

    // === Helpers ===

    private HikariDataSource createR2UpgradedSwitchingDatabase(String jobId) {
        String dbName = "search_r2_sw_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");
        hc.setMaximumPoolSize(4);
        HikariDataSource ds = new HikariDataSource(hc);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Bootstrap full schema then roll back active_cutover_bypass_owner to obtain authentic SEARCH-03-R2 schema.
        new ResourceDatabasePopulator(new ClassPathResource("db/search-consumer-schema.sql")).execute(ds);
        jdbc.execute("ALTER TABLE search_index_generation DROP COLUMN active_cutover_bypass_owner");

        long nowMs = clock.millis();
        // Seed R2 state right after ES alias switched to g2, BEFORE SQL local activation:
        // active_generation = 1, cutover_state = SWITCHING_ALIAS, job.phase = SWITCHING_ALIAS.
        jdbc.update("""
                INSERT INTO search_index_generation (
                    alias_name, active_generation, active_physical_index,
                    require_cold_visibility_guard, cutover_state, cutover_job_id,
                    target_generation, target_physical_index, cutover_started_at_ms,
                    active_cache_invalidation_status, active_cache_invalidation_attempts,
                    updated_at_ms
                ) VALUES (?, 1, ?, 0, 'SWITCHING_ALIAS', ?, 2, ?, ?, 'DONE', 0, ?)
                """, ALIAS, G1, jobId, G2, nowMs - 20_000L, nowMs - 15_000L);

        jdbc.update("""
                INSERT INTO search_rebuild_job (
                    job_id, alias_name, active_lock_key, client_request_id,
                    snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                    last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                    source_generation, source_physical_index,
                    candidate_generation, candidate_physical_index,
                    phase, lease_owner, lease_until_ms, lease_generation,
                    alias_switch_uncertain, switch_attempts,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, 1, 1, 1, 1, 0, 0, 1, ?, 2, ?, 'SWITCHING_ALIAS', 'old-r2-inst', ?, 1, 1, 1, ?, ?)
                """, jobId, ALIAS, ALIAS, "req-" + jobId, G1, G2, nowMs - 10_000L, nowMs - 20_000L, nowMs - 15_000L);

        // Apply additive R3 upgrade DDL.
        ResourceDatabasePopulator r3Upgrade = new ResourceDatabasePopulator(
                new ClassPathResource("db/search-rebuild-03-r3-upgrade.sql"));
        r3Upgrade.execute(ds);
        return ds;
    }

    private void indexDocDirectly(String physicalIndex, long articleId, long sourceRevision, String title) throws Exception {
        esStub.createIndex(physicalIndex);
        SearchIndexDocument doc = SearchIndexDocument.upsert(
                articleId, sourceRevision, title, "Summary " + title, "Body " + title,
                1L, "Tech", List.of("ES"), null, 10L, clock.millis());
        String body = MAPPER.writeValueAsString(doc);
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(esStub.baseUrl() + "/" + physicalIndex + "/_doc/" + articleId + "?refresh=true"))
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).isBetween(200, 299);
    }

    private RebuildJobRecord advanceUntilPhase(SearchRebuildService svc, String jobId, RebuildPhase target) {
        RebuildJobRecord current = svc.findJob(jobId).orElseThrow();
        for (int i = 0; i < 16 && current.phase() != target; i++) {
            current = svc.advanceJob(jobId).job();
        }
        assertThat(current.phase()).isEqualTo(target);
        return current;
    }

    private PublicArticleSearchService createPublicSearchService() {
        SearchMetrics metrics = new SearchMetrics(new SimpleMeterRegistry());
        ObjectProvider<StringRedisTemplate> cacheProvider = singleObjectProvider(cacheRedisTemplate);
        ArticleSearchCache cache = new ArticleSearchCache(cacheProvider, MAPPER, cacheProps, metrics, ENV);
        SearchQueryPlanner planner = new SearchQueryPlanner(queryProps, cacheProps, esProps, ENV);
        ElasticsearchArticleSearchRepository searchRepo = new ElasticsearchArticleSearchRepository(esClient, esProps);
        return new PublicArticleSearchService(planner, cache, searchRepo, visibilityClient, queryProps, metrics);
    }

    private int countEsSearchRequests() {
        return (int) esStub.requests().stream()
                .filter(r -> r.path().endsWith("/_search"))
                .count();
    }

    private static <T> ObjectProvider<T> singleObjectProvider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) throws BeansException {
                return instance;
            }

            @Override
            public T getIfAvailable() throws BeansException {
                return instance;
            }

            @Override
            public T getIfUnique() throws BeansException {
                return instance;
            }

            @Override
            public T getObject() throws BeansException {
                return instance;
            }
        };
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void advance(Duration delta) {
            now.updateAndGet(cur -> cur.plus(delta));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
