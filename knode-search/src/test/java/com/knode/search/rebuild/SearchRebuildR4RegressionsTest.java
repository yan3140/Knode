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
import com.knode.search.consumer.persistence.SearchIndexDocument;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRebuildR4RegressionsTest {
    private static final String ENV = "it-rebuild-r4";
    private static final String ALIAS = "knode_it_r4_articles_search";
    private static final String G1 = ALIAS + "_g1";
    private static final String G2 = ALIAS + "_g2";
    private static final String G3 = ALIAS + "_g3";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate txTemplate;
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
    private SearchConsumerRepository consumerRepository;
    private SearchRebuildRepository rebuildRepository;
    private ContentEventParser eventParser;
    private ElasticsearchArticleIndexWriter indexWriter;
    private SearchCacheInvalidator cacheInvalidator;
    private SearchIndexTaskWorker indexWorker;
    private HttpContentSnapshotClient snapshotClient;
    private ElasticsearchIndexAdminClient indexAdminClient;
    private ContentVisibilityClient visibilityClient;
    private Map<String, VisibilityDecision> visibilityTable;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "search_rb_r4_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");
        hc.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(hc);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/search-consumer-schema.sql"));
        populator.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        txTemplate = new TransactionTemplate(txManager);
        txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        clock = new MutableClock(Instant.parse("2026-11-26T13:30:00Z"));
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
        snapshotStub.setExpectedBearerToken("rb-r4-token");

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
        rebuildProps.setInstanceId("rb-r4-inst-1");
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
                return "rb-r4-token";
            }

            @Override
            public void invalidate(String audience, List<String> permissions) {
            }
        };

        consumerRepository = new SearchConsumerRepository(jdbcTemplate, MAPPER);
        rebuildRepository = new SearchRebuildRepository(jdbcTemplate, consumerRepository, MAPPER);
        eventParser = new ContentEventParser();

        ObjectProvider<StringRedisTemplate> cacheProvider = singleObjectProvider(cacheRedisTemplate);
        cacheInvalidator = new SearchCacheInvalidator(cacheProvider, ENV);
        indexWriter = new ElasticsearchArticleIndexWriter(esClient, esProps, consumerProps, MAPPER);
        indexWorker = new SearchIndexTaskWorker(
                consumerRepository, txTemplate, indexWriter, cacheInvalidator, consumerProps, ALIAS, clock);
        snapshotClient = new HttpContentSnapshotClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clientsProps,
                tokenClient
        );
        indexAdminClient = new ElasticsearchIndexAdminClient(esClient, MAPPER);

        txTemplate.executeWithoutResult(status ->
                consumerRepository.ensureAndLockGeneration(ALIAS, G1, clock.millis()));
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
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * SEARCH-03-R4 Regression 1A:
     * An upgraded SEARCH-03-R2 job is in CACHE_INVALIDATING while search_index_generation has
     * active_cache_invalidation_status = PENDING and active_cutover_bypass_owner = NULL, and Redis still
     * holds legacy "<alias>:g2".
     * When SearchRebuildService#advanceJob runs BEFORE background SearchIndexTaskWorker:
     * 1) If Redis INCR still fails, legacy "<alias>:g2" and PENDING state MUST be preserved.
     * 2) Once Redis recovers, SearchRebuildService MUST advance epoch, delete legacy "<alias>:g2",
     *    mark generation DONE and job COMPLETED, and restore new-epoch caching on zero-DB public search.
     */
    @Test
    void clearsLegacyR2CutoverBypassWhenRebuildRecoveryRunsBeforeBackgroundCompensationAfterUpgrade() throws Exception {
        // 1) Warm stale empty-result cache on G1 (epoch 0) before R2 switched to G2.
        PublicArticleSearchService publicSearch = createPublicSearchService();
        assertThat(publicSearch.search("R2UpgradedLegacyToken", 1, 5, "relevance", "trace-r2-warm-1").rows()).isEmpty();
        assertThat(publicSearch.search("R2UpgradedLegacyToken", 1, 5, "relevance", "trace-r2-warm-2").rows()).isEmpty();
        int esBeforeWarmHit = countEsSearchRequests();
        assertThat(publicSearch.search("R2UpgradedLegacyToken", 1, 5, "relevance", "trace-r2-warm-hit").rows()).isEmpty();
        assertThat(countEsSearchRequests()).isEqualTo(esBeforeWarmHit);

        // 2) Create a fresh R2-era H2 database (WITHOUT active_cutover_bypass_owner), seed mid-PENDING R2 job,
        //    then run db/search-rebuild-03-r3-upgrade.sql.
        try (HikariDataSource r2Ds = createR2UpgradedDatabase("rb-r2-upgrade-first")) {
            JdbcTemplate r2Jdbc = new JdbcTemplate(r2Ds);
            TransactionTemplate r2Tx = new TransactionTemplate(new DataSourceTransactionManager(r2Ds));
            r2Tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            SearchConsumerRepository r2ConsumerRepo = new SearchConsumerRepository(r2Jdbc, MAPPER);
            SearchRebuildRepository r2RebuildRepo = new SearchRebuildRepository(r2Jdbc, r2ConsumerRepo, MAPPER);
            SearchIndexTaskWorker r2Worker = new SearchIndexTaskWorker(
                    r2ConsumerRepo, r2Tx, indexWriter, cacheInvalidator, consumerProps, ALIAS, clock);
            SearchRebuildService r2RebuildService = new SearchRebuildService(
                    r2RebuildRepo, r2ConsumerRepo, r2Tx, snapshotClient, eventParser, r2Worker,
                    indexAdminClient, cacheInvalidator, consumerProps, rebuildProps, ALIAS,
                    "inst-r4-upgraded-recovery", MAPPER, clock);

            // Confirm upgraded DB state: active_generation=2, PENDING, active_cutover_bypass_owner=NULL.
            IndexGenerationState upgradedGen = r2ConsumerRepo.findGenerationState(ALIAS).orElseThrow();
            assertThat(upgradedGen.activeGeneration()).isEqualTo(2L);
            assertThat(upgradedGen.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);
            assertThat(upgradedGen.activeCutoverBypassOwner()).isNull();

            // ES alias already points to G2 with the new article; Redis holds legacy R2 owner "<alias>:g2" (no watermark).
            indexDocDirectly(G2, 9401L, 2L, "R2UpgradedLegacyToken In G2");
            visibilityTable.put("9401", new VisibilityDecision("9401", "2", "PUBLIC", "READABLE"));
            esStub.setAlias(ALIAS, G2);
            String legacyOwner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L);
            cacheRedis.putRaw(cacheInvalidator.cutoverBypassKey(), legacyOwner);
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassWatermarkKey())).isFalse();

            // Public search (zero consumer DB) bypasses stale :e0 empty cache via legacy "<alias>:g2".
            PageResult duringBypass = publicSearch.search("R2UpgradedLegacyToken", 1, 5, "relevance", "trace-r2-bypass");
            assertThat(duringBypass.rows()).hasSize(1);
            assertThat(duringBypass.rows().get(0).id()).isEqualTo(9401L);

            // 3) While Redis INCR still fails, rebuild recovery MUST keep legacy "<alias>:g2" & PENDING intact.
            cacheRedis.failCommand("INCR", "ERR redis incr still failing after upgrade");
            SearchRebuildService.StepOutcome pendingOut = r2RebuildService.advanceJob("rb-r2-upgrade-first");
            assertThat(pendingOut.status()).isEqualTo(SearchRebuildService.StepStatus.CACHE_INVALIDATION_PENDING);
            assertThat(pendingOut.job().phase()).isEqualTo(RebuildPhase.CACHE_INVALIDATING);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(legacyOwner);
            IndexGenerationState stillPending = r2ConsumerRepo.findGenerationState(ALIAS).orElseThrow();
            assertThat(stillPending.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);
            assertThat(stillPending.activeCutoverBypassOwner()).isNull();

            // 4) Clear Redis INCR fault -> SearchRebuildService runs BEFORE background SearchIndexTaskWorker!
            cacheRedis.clearFaults();
            SearchRebuildService.StepOutcome completedOut = r2RebuildService.advanceJob("rb-r2-upgrade-first");
            assertThat(completedOut.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
            assertThat(completedOut.job().phase()).isEqualTo(RebuildPhase.COMPLETED);

            // CRITICAL: epoch advanced, legacy "<alias>:g2" deleted, watermark recorded, DB DONE!
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:0:CLEARED");

            IndexGenerationState doneGen = r2ConsumerRepo.findGenerationState(ALIAS).orElseThrow();
            assertThat(doneGen.activeGeneration()).isEqualTo(2L);
            assertThat(doneGen.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
            assertThat(doneGen.activeCutoverBypassOwner()).isNull();

            // Background worker sees zero remaining pending generation compensations.
            SearchIndexTaskWorker.CacheCompensationSummary workerSummary = r2Worker.processPendingCacheInvalidations();
            assertThat(workerSummary.candidateCount()).isZero();

            // Public search (zero consumer DB) resumes caching on new epoch :e1 and hits Redis on 3rd request.
            PublicArticleSearchService freshPublicSearch = createPublicSearchService();
            PageResult e1First = freshPublicSearch.search("R2UpgradedLegacyToken", 1, 5, "relevance", "trace-r2-e1-1");
            PageResult e1Second = freshPublicSearch.search("R2UpgradedLegacyToken", 1, 5, "relevance", "trace-r2-e1-2");
            assertThat(e1First.rows()).hasSize(1);
            assertThat(e1Second.rows()).hasSize(1);
            int esBeforeE1Hit = countEsSearchRequests();
            PageResult e1Hit = freshPublicSearch.search("R2UpgradedLegacyToken", 1, 5, "relevance", "trace-r2-e1-3");
            assertThat(e1Hit.rows()).hasSize(1);
            assertThat(e1Hit.rows().get(0).id()).isEqualTo(9401L);
            assertThat(countEsSearchRequests()).isEqualTo(esBeforeE1Hit);
        }
    }

    /**
     * SEARCH-03-R4 Regression 1B:
     * Companion check confirming that when background SearchIndexTaskWorker runs BEFORE
     * SearchRebuildService#advanceJob on the same upgraded R2 pending state, legacy "<alias>:g2" is
     * also cleared cleanly and SearchRebuildService finishes CACHE_INVALIDATING -> COMPLETED without
     * advancing epoch a second time.
     */
    @Test
    void clearsLegacyR2CutoverBypassWhenBackgroundCompensationRunsBeforeRebuildRecoveryAfterUpgrade() throws Exception {
        try (HikariDataSource r2Ds = createR2UpgradedDatabase("rb-r2-worker-first")) {
            JdbcTemplate r2Jdbc = new JdbcTemplate(r2Ds);
            TransactionTemplate r2Tx = new TransactionTemplate(new DataSourceTransactionManager(r2Ds));
            r2Tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            SearchConsumerRepository r2ConsumerRepo = new SearchConsumerRepository(r2Jdbc, MAPPER);
            SearchRebuildRepository r2RebuildRepo = new SearchRebuildRepository(r2Jdbc, r2ConsumerRepo, MAPPER);
            SearchIndexTaskWorker r2Worker = new SearchIndexTaskWorker(
                    r2ConsumerRepo, r2Tx, indexWriter, cacheInvalidator, consumerProps, ALIAS, clock);
            SearchRebuildService r2RebuildService = new SearchRebuildService(
                    r2RebuildRepo, r2ConsumerRepo, r2Tx, snapshotClient, eventParser, r2Worker,
                    indexAdminClient, cacheInvalidator, consumerProps, rebuildProps, ALIAS,
                    "inst-r4-worker-first", MAPPER, clock);

            indexDocDirectly(G2, 9403L, 2L, "R2WorkerFirstArticle");
            visibilityTable.put("9403", new VisibilityDecision("9403", "2", "PUBLIC", "READABLE"));
            esStub.setAlias(ALIAS, G2);
            cacheRedis.putRaw(cacheInvalidator.cutoverBypassKey(), SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L));

            // 1) Background compensation runs first.
            SearchIndexTaskWorker.CacheCompensationSummary comp = r2Worker.processPendingCacheInvalidations();
            assertThat(comp.completedCount()).isEqualTo(1);
            assertThat(comp.failedCount()).isZero();
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();

            // 2) Rebuild recovery runs second and transitions job to COMPLETED without extra epoch INCR.
            SearchRebuildService.StepOutcome completedOut = r2RebuildService.advanceJob("rb-r2-worker-first");
            assertThat(completedOut.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
            assertThat(completedOut.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
        }
    }

    /**
     * SEARCH-03-R4 Regression 2:
     * 1) Instance A passes local verifyLeaseActiveInTx in SWITCHING_ALIAS for g2:a1 and PAUSES right
     *    before sending enterCutoverBypass("<alias>:g2:a1", 2, 1) to Redis.
     * 2) Lease expires -> Instance B takes over the same job: attempt 1 hits confirmed ES 4xx rejection
     *    and reverts; Instance B retries the SAME candidate g2 as attempt 2 (g2:a2), holds cutover_bypass
     *    while Redis INCR fails, then advances epoch, clears cutover_bypass, and completes the job.
     * 3) Public search warms the new-epoch (:e1) Redis cache for the g2 article and serves cache hits.
     * 4) Release paused Instance A -> its delayed enterCutoverBypass("<alias>:g2:a1", 2, 1) arrives at
     *    Redis AFTER a2 has already deleted cutover_bypass!
     *    Redis MUST atomically reject the stale a1 write via the durable watermark, keeping
     *    cutover_bypass absent, leaving DB/ES untouched, and allowing same-parameter public search
     *    (zero consumer DB) to keep hitting the new-epoch cache with zero ES search calls.
     * 5) Subsequent g3 rebuild (g3:a1) MUST still acquire cutover_bypass and complete normally.
     */
    @Test
    void rejectsLateAttempt1CutoverBypassWriteAfterAttempt2CompletesAndClearsBypass() throws Exception {
        // 1) Warm stale empty-result cache on G1 (epoch 0) for "LateAttempt1FenceToken".
        PublicArticleSearchService publicSearch = createPublicSearchService();
        assertThat(publicSearch.search("LateAttempt1FenceToken", 1, 5, "relevance", "trace-a1-warm-1").rows()).isEmpty();
        assertThat(publicSearch.search("LateAttempt1FenceToken", 1, 5, "relevance", "trace-a1-warm-2").rows()).isEmpty();
        int esBeforeWarmHit = countEsSearchRequests();
        assertThat(publicSearch.search("LateAttempt1FenceToken", 1, 5, "relevance", "trace-a1-warm-hit").rows()).isEmpty();
        assertThat(countEsSearchRequests()).isEqualTo(esBeforeWarmHit);

        // 2) Seed new G2 article in snapshot stub and start rb-1 on Instance A up to SWITCHING_ALIAS (a1).
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(9402L, 2L, "LateAttempt1FenceToken Published In G2",
                        "Sum G2", "Body G2", 1L, "Tech", List.of("ES"), 25L, 1L, "2026-10-01T00:00:00Z")
        ));
        visibilityTable.put("9402", new VisibilityDecision("9402", "2", "PUBLIC", "READABLE"));

        CountDownLatch instanceAPausedBeforeBypassWrite = new CountDownLatch(1);
        CountDownLatch releaseInstanceA = new CountDownLatch(1);
        AtomicReference<String> pausedAttempt1Owner = new AtomicReference<>();

        SearchRebuildService instanceA = new SearchRebuildService(
                rebuildRepository,
                consumerRepository,
                txTemplate,
                snapshotClient,
                eventParser,
                indexWorker,
                indexAdminClient,
                cacheInvalidator,
                consumerProps,
                rebuildProps,
                ALIAS,
                "inst-late-a1",
                MAPPER,
                clock
        ) {
            @Override
            protected void beforeEnterCutoverBypassInSwitch(RebuildJobRecord job, String bypassOwner) {
                pausedAttempt1Owner.set(bypassOwner);
                instanceAPausedBeforeBypassWrite.countDown();
                try {
                    assertThat(releaseInstanceA.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        SearchRebuildService instanceB = createRebuildService("inst-winner-a2");

        RebuildJobRecord job = instanceA.startRebuild("req-r4-late-a1");
        RebuildJobRecord atAttempt1Switch = advanceUntilPhase(instanceA, job.jobId(), RebuildPhase.SWITCHING_ALIAS);
        assertThat(atAttempt1Switch.candidateGeneration()).isEqualTo(2L);
        assertThat(atAttempt1Switch.switchAttempts()).isEqualTo(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 3) Instance A enters SWITCHING_ALIAS, passes verifyLeaseActiveInTx, and pauses right BEFORE
            //    sending enterCutoverBypass("<alias>:g2:a1", 2, 1) to Redis.
            Future<SearchRebuildService.StepOutcome> staleAttempt1Future =
                    pool.submit(() -> instanceA.advanceJob(job.jobId()));
            assertThat(instanceAPausedBeforeBypassWrite.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pausedAttempt1Owner.get())
                    .isEqualTo(SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 1L));

            // 4) Lease expires -> Instance B takes over rb-1:
            //    Attempt 1 on B receives confirmed ES 4xx pre-switch rejection -> rolls back to CUTOVER_PAUSING.
            clock.advance(Duration.ofSeconds(15));
            AtomicBoolean rejectFirstSwitch = new AtomicBoolean(true);
            esStub.setInterceptor((req, defaultAction) -> {
                if ("POST".equalsIgnoreCase(req.method())
                        && "/_aliases".equals(req.path())
                        && rejectFirstSwitch.compareAndSet(true, false)) {
                    return StatefulElasticsearchStub.StubResponse.status(
                            400, "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"simulated_a1_reject\"},\"status\":400}");
                }
                return defaultAction.get();
            });
            SearchRebuildService.StepOutcome bAttempt1Reject = instanceB.advanceJob(job.jobId());
            assertThat(bAttempt1Reject.status()).isEqualTo(SearchRebuildService.StepStatus.PRE_SWITCH_FAILED_REVERTED);
            assertThat(bAttempt1Reject.job().phase()).isEqualTo(RebuildPhase.CUTOVER_PAUSING);
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:CLEARED");

            // 5) Instance B retries THE SAME candidate G2 as Attempt 2 (a2):
            //    First inject Redis INCR failure to prove active a2 keeps cutover_bypass until INCR succeeds.
            esStub.clearInterceptor();
            cacheRedis.failCommand("INCR", "ERR redis incr down during a2");
            RebuildJobRecord atA2Pending =
                    advanceUntilPhase(instanceB, job.jobId(), RebuildPhase.CACHE_INVALIDATING);
            assertThat(atA2Pending.switchAttempts()).isEqualTo(2);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
            String attempt2Owner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 2L);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(attempt2Owner);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:2:ACTIVE");

            // Clear INCR fault -> a2 completes, advances epoch to 1, and clears cutover_bypass!
            cacheRedis.clearFaults();
            SearchRebuildService.StepOutcome a2Completed = instanceB.advanceJob(job.jobId());
            assertThat(a2Completed.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
            assertThat(a2Completed.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:2:CLEARED");

            IndexGenerationState doneG2 = consumerRepository.findGenerationState(ALIAS).orElseThrow();
            assertThat(doneG2.activeGeneration()).isEqualTo(2L);
            assertThat(doneG2.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
            assertThat(doneG2.activeCutoverBypassOwner()).isNull();

            // 6) Warm new-epoch (:e1) public search cache for "LateAttempt1FenceToken" and confirm Redis hit.
            PublicArticleSearchService freshPublicSearch = createPublicSearchService();
            PageResult e1First = freshPublicSearch.search("LateAttempt1FenceToken", 1, 5, "relevance", "trace-a2-e1-1");
            PageResult e1Second = freshPublicSearch.search("LateAttempt1FenceToken", 1, 5, "relevance", "trace-a2-e1-2");
            assertThat(e1First.rows()).hasSize(1);
            assertThat(e1Second.rows()).hasSize(1);
            int esBeforeHit1 = countEsSearchRequests();
            PageResult e1HitBeforeLateA1 = freshPublicSearch.search("LateAttempt1FenceToken", 1, 5, "relevance", "trace-a2-e1-3");
            assertThat(e1HitBeforeLateA1.rows()).hasSize(1);
            assertThat(e1HitBeforeLateA1.rows().get(0).id()).isEqualTo(9402L);
            assertThat(countEsSearchRequests()).isEqualTo(esBeforeHit1);

            // 7) Release paused Instance A -> its delayed enterCutoverBypass("<alias>:g2:a1", 2, 1) hits Redis
            //    AFTER a2 completed and deleted cutover_bypass!
            releaseInstanceA.countDown();
            SearchRebuildService.StepOutcome lateA1Outcome = staleAttempt1Future.get(10, TimeUnit.SECONDS);
            assertThat(lateA1Outcome.status()).isEqualTo(SearchRebuildService.StepStatus.LEASE_LOST_FENCED);

            // CRITICAL ASSERTIONS:
            // - cutover_bypass MUST stay absent (late a1 write was atomically rejected by Redis watermark!)
            // - watermark MUST remain "2:2:CLEARED"
            // - DB job remains COMPLETED, generation remains g2 / DONE / NORMAL, ES alias remains {G2}
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:2:CLEARED");
            assertThat(rebuildRepository.findJob(job.jobId()).orElseThrow().phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
            IndexGenerationState afterLateA1 = consumerRepository.findGenerationState(ALIAS).orElseThrow();
            assertThat(afterLateA1.activeGeneration()).isEqualTo(2L);
            assertThat(afterLateA1.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
            assertThat(afterLateA1.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);

            // Same-parameter public search (zero consumer DB) STILL hits the new-epoch (:e1) Redis cache!
            int esBeforeHitAfterLateA1 = countEsSearchRequests();
            PageResult e1HitAfterLateA1 = freshPublicSearch.search(
                    "LateAttempt1FenceToken", 1, 5, "relevance", "trace-after-late-a1");
            assertThat(e1HitAfterLateA1.rows()).hasSize(1);
            assertThat(e1HitAfterLateA1.rows().get(0).id()).isEqualTo(9402L);
            assertThat(countEsSearchRequests()).isEqualTo(esBeforeHitAfterLateA1);

            // 8) Next generation rebuild (g3:a1) STILL succeeds cleanly past the "2:2:CLEARED" watermark.
            snapshotStub.setLivePublicArticles(List.of(
                    LoopbackContentSnapshotStub.snapshotItem(9402L, 2L, "LateAttempt1FenceToken Published In G2",
                            "Sum G2", "Body G2", 1L, "Tech", List.of("ES"), 25L, 1L, "2026-10-01T00:00:00Z"),
                    LoopbackContentSnapshotStub.snapshotItem(9404L, 3L, "G3SubsequentRebuildArticle",
                            "Sum G3", "Body G3", 1L, "Tech", List.of("ES"), 30L, 1L, "2026-10-02T00:00:00Z")
            ));
            visibilityTable.put("9404", new VisibilityDecision("9404", "3", "PUBLIC", "READABLE"));

            RebuildJobRecord g3Job = instanceB.startRebuild("req-r4-g3-after-a2");
            RebuildJobRecord g3Done = instanceB.runUntilTerminalOrLimit(g3Job.jobId(), 20);
            assertThat(g3Done.phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("3:1:CLEARED");
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("2");
        } finally {
            releaseInstanceA.countDown();
            pool.shutdownNow();
        }
    }

    // === Helpers ===

    private HikariDataSource createR2UpgradedDatabase(String jobId) {
        String dbName = "search_r2_up_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");
        hc.setMaximumPoolSize(4);
        HikariDataSource ds = new HikariDataSource(hc);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Bootstrap SEARCH-03-R2 schema (BEFORE active_cutover_bypass_owner existed).
        jdbc.execute("""
                CREATE TABLE search_index_generation (
                    alias_name VARCHAR(128) NOT NULL,
                    active_generation BIGINT NOT NULL DEFAULT 1,
                    active_physical_index VARCHAR(128) NOT NULL,
                    require_cold_visibility_guard TINYINT(1) NOT NULL DEFAULT 0,
                    cutover_state VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
                    cutover_job_id VARCHAR(64) NULL,
                    previous_generation BIGINT NULL,
                    previous_physical_index VARCHAR(128) NULL,
                    target_generation BIGINT NULL,
                    target_physical_index VARCHAR(128) NULL,
                    cutover_started_at_ms BIGINT NULL,
                    active_cache_invalidation_status VARCHAR(16) NOT NULL DEFAULT 'DONE',
                    active_cache_invalidation_attempts INT NOT NULL DEFAULT 0,
                    active_cache_invalidation_due_at_ms BIGINT NULL,
                    active_cache_last_error VARCHAR(512) NULL,
                    updated_at_ms BIGINT NOT NULL,
                    PRIMARY KEY (alias_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE search_index_task (
                    index_generation BIGINT NOT NULL DEFAULT 1,
                    article_id BIGINT NOT NULL,
                    target_physical_index VARCHAR(128) NOT NULL DEFAULT '',
                    target_projection_revision BIGINT NOT NULL,
                    operation VARCHAR(16) NOT NULL,
                    payload_json LONGTEXT NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    attempt_count INT NOT NULL DEFAULT 0,
                    max_attempts INT NOT NULL DEFAULT 5,
                    next_retry_at_ms BIGINT NOT NULL,
                    lease_until_ms BIGINT NULL,
                    claim_generation BIGINT NOT NULL DEFAULT 0,
                    es_applied_revision BIGINT NOT NULL DEFAULT 0,
                    cache_invalidation_status VARCHAR(16) NOT NULL DEFAULT 'DONE',
                    cache_invalidation_attempts INT NOT NULL DEFAULT 0,
                    cache_invalidation_due_at_ms BIGINT NULL,
                    cache_last_error VARCHAR(512) NULL,
                    last_error VARCHAR(512) NULL,
                    created_at_ms BIGINT NOT NULL,
                    updated_at_ms BIGINT NOT NULL,
                    PRIMARY KEY (index_generation, article_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE search_rebuild_job (
                    job_id VARCHAR(64) NOT NULL,
                    alias_name VARCHAR(128) NOT NULL,
                    active_lock_key VARCHAR(128) NULL,
                    client_request_id VARCHAR(128) NOT NULL,
                    snapshot_id VARCHAR(64) NULL,
                    snapshot_expires_at_ms BIGINT NULL,
                    snapshot_page_cursor VARCHAR(1024) NULL,
                    snapshot_done TINYINT(1) NOT NULL DEFAULT 0,
                    snapshot_pages_imported INT NOT NULL DEFAULT 0,
                    snapshot_items_imported BIGINT NOT NULL DEFAULT 0,
                    incremental_cursor VARCHAR(1024) NULL,
                    incremental_cursor_expires_at_ms BIGINT NULL,
                    last_commit_sequence BIGINT NOT NULL DEFAULT 0,
                    incremental_commits_applied BIGINT NOT NULL DEFAULT 0,
                    incremental_events_applied BIGINT NOT NULL DEFAULT 0,
                    source_generation BIGINT NOT NULL,
                    source_physical_index VARCHAR(128) NOT NULL,
                    candidate_generation BIGINT NOT NULL,
                    candidate_physical_index VARCHAR(128) NOT NULL,
                    phase VARCHAR(32) NOT NULL,
                    lease_owner VARCHAR(128) NULL,
                    lease_until_ms BIGINT NULL,
                    lease_generation BIGINT NOT NULL DEFAULT 0,
                    alias_switch_uncertain TINYINT(1) NOT NULL DEFAULT 0,
                    switch_attempts INT NOT NULL DEFAULT 0,
                    last_error VARCHAR(512) NULL,
                    superseded_by_job_id VARCHAR(64) NULL,
                    created_at_ms BIGINT NOT NULL,
                    updated_at_ms BIGINT NOT NULL,
                    PRIMARY KEY (job_id),
                    CONSTRAINT uk_search_rebuild_active_alias UNIQUE (active_lock_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        long nowMs = clock.millis();
        jdbc.update("""
                INSERT INTO search_index_generation (
                    alias_name, active_generation, active_physical_index,
                    require_cold_visibility_guard, cutover_state,
                    previous_generation, previous_physical_index,
                    active_cache_invalidation_status, active_cache_invalidation_attempts,
                    active_cache_invalidation_due_at_ms, active_cache_last_error, updated_at_ms
                ) VALUES (?, 2, ?, 1, 'NORMAL', 1, ?, 'PENDING', 1, ?, 'CUTOVER_CACHE_EPOCH_INCR_Legacy', ?)
                """, ALIAS, G2, G1, nowMs, nowMs);

        jdbc.update("""
                INSERT INTO search_rebuild_job (
                    job_id, alias_name, active_lock_key, client_request_id,
                    snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                    last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                    source_generation, source_physical_index,
                    candidate_generation, candidate_physical_index,
                    phase, lease_owner, lease_until_ms, lease_generation,
                    alias_switch_uncertain, switch_attempts,
                    last_error, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, 1, 1, 1, 1, 0, 0, 1, ?, 2, ?, 'CACHE_INVALIDATING', 'old-r2-inst', ?, 1, 0, 1,
                          'CUTOVER_CACHE_EPOCH_INCR_Legacy', ?, ?)
                """, jobId, ALIAS, ALIAS, "req-" + jobId, G1, G2, nowMs - 10_000L, nowMs - 20_000L, nowMs - 10_000L);

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

    private SearchRebuildService createRebuildService(String instanceId) {
        return new SearchRebuildService(
                rebuildRepository,
                consumerRepository,
                txTemplate,
                snapshotClient,
                eventParser,
                indexWorker,
                indexAdminClient,
                cacheInvalidator,
                consumerProps,
                rebuildProps,
                ALIAS,
                instanceId,
                MAPPER,
                clock
        );
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
