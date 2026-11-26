package com.knode.search.rebuild;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.PageResult;
import com.knode.search.model.QueryBudget;
import com.knode.search.rebuild.client.HttpContentSnapshotClient;
import com.knode.search.rebuild.config.SearchRebuildProperties;
import com.knode.search.rebuild.es.ElasticsearchIndexAdminClient;
import com.knode.search.rebuild.es.SearchIndexAdminClient;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRebuildR3RegressionsTest {
    private static final String ENV = "it-rebuild-r3";
    private static final String ALIAS = "knode_it_r3_articles_search";
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
        String dbName = "search_rb_r3_" + UUID.randomUUID().toString().replace("-", "");
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

        clock = new MutableClock(Instant.parse("2026-11-26T12:30:00Z"));
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
        snapshotStub.setExpectedBearerToken("rb-r3-token");

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
        rebuildProps.setInstanceId("rb-r3-inst-1");
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
                return "rb-r3-token";
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
     * SEARCH-03-R3 Regression 1:
     * g2 and its birth-certificate attach fence already exist while job rb-1 is still in INIT.
     * Instance A pauses inside ElasticsearchIndexAdminClient#ensurePhysicalIndex(g2) after HEAD /g2 -> 200.
     * Lease expires -> Instance B takes over INIT, drives g2 to SWITCHING_ALIAS, hits partial failure
     * leaving an empty alias and one timed-out empty-alias repair request (captured), recovers g2,
     * and then completes a subsequent g3 cutover.
     * Releasing expired Instance A (and even replaying a delayed PUT /g2) MUST NEVER recreate
     * g2__attach_fence, and replaying the delayed g2 empty-alias repair MUST fail with HTTP 404
     * leaving ES alias uniquely on {g3}, activeGeneration = 3, and gate = NORMAL.
     */
    @Test
    void preventsExpiredInitInstanceFromRecreatingConsumedAttachFenceOrAllowingLateRepairReattach() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(9102L, 2L, "G2TargetArticle", "Sum G2", "Body G2",
                        1L, "Tech", List.of("ES"), 10L, 1L, "2026-10-01T00:00:00Z")
        ));
        visibilityTable.put("9102", new VisibilityDecision("9102", "2", "PUBLIC", "READABLE"));

        SearchElasticsearchProperties longTimeoutEsProps = new SearchElasticsearchProperties();
        longTimeoutEsProps.setUrl(esStub.baseUrl());
        longTimeoutEsProps.setIndex(ALIAS);
        longTimeoutEsProps.setConnectTimeoutMs(1000);
        longTimeoutEsProps.setConnectionRequestTimeoutMs(1000);
        longTimeoutEsProps.setResponseTimeoutMs(20000);
        ElasticsearchClient esClientA = new SearchRuntimeConfig().elasticsearchClient(longTimeoutEsProps, queryProps);
        ElasticsearchIndexAdminClient indexAdminClientA = new ElasticsearchIndexAdminClient(esClientA, MAPPER);

        SearchRebuildService serviceA = new SearchRebuildService(
                rebuildRepository,
                consumerRepository,
                txTemplate,
                snapshotClient,
                eventParser,
                indexWorker,
                indexAdminClientA,
                cacheInvalidator,
                consumerProps,
                rebuildProps,
                ALIAS,
                "inst-init-A",
                MAPPER,
                clock
        );
        SearchRebuildService serviceB = createRebuildService("inst-init-B");

        // 1) Start job rb-1 (candidate g2, phase = INIT) and pre-create g2 + g2__attach_fence as if a prior
        //    INIT attempt created the physical index before failing snapshot creation.
        RebuildJobRecord job = serviceA.startRebuild("req-r3-init-g2");
        assertThat(job.phase()).isEqualTo(RebuildPhase.INIT);
        assertThat(job.candidatePhysicalIndex()).isEqualTo(G2);
        indexAdminClient.ensurePhysicalIndex(G2);
        assertThat(esStub.aliasTargets(SearchIndexAdminClient.attachFenceAlias(G2))).containsExactly(G2);

        // 2) Pause Instance A right inside ElasticsearchIndexAdminClient#ensurePhysicalIndex(G2) on HEAD /g2!
        CountDownLatch instanceAEnteredHead = new CountDownLatch(1);
        CountDownLatch releaseInstanceA = new CountDownLatch(1);
        AtomicBoolean pauseFirstHeadForG2 = new AtomicBoolean(true);

        esStub.setInterceptor((req, defaultAction) -> {
            if ("HEAD".equalsIgnoreCase(req.method())
                    && ("/" + G2).equals(req.path())
                    && pauseFirstHeadForG2.compareAndSet(true, false)) {
                StatefulElasticsearchStub.StubResponse headResp = defaultAction.get();
                instanceAEnteredHead.countDown();
                try {
                    assertThat(releaseInstanceA.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return headResp;
            }
            return defaultAction.get();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SearchRebuildService.StepOutcome> staleInitFuture =
                    pool.submit(() -> serviceA.advanceJob(job.jobId()));
            assertThat(instanceAEnteredHead.await(5, TimeUnit.SECONDS)).isTrue();

            // 3) Expire A's lease -> Instance B takes over rb-1 in INIT (g2 + fence already exist!) and
            //    progresses through INIT -> ... -> SWITCHING_ALIAS.
            clock.advance(Duration.ofSeconds(15));
            RebuildJobRecord atSwitching = advanceUntilPhase(serviceB, job.jobId(), RebuildPhase.SWITCHING_ALIAS);
            assertThat(atSwitching.leaseOwner()).isEqualTo("inst-init-B");
            assertThat(esStub.aliasTargets(SearchIndexAdminClient.attachFenceAlias(G2))).containsExactly(G2);

            // 4) Manufacture partial failure leaving empty alias {} + one timed-out empty-alias repair request!
            AtomicInteger aliasPostCounter = new AtomicInteger(0);
            AtomicReference<String> capturedLateRepairBody = new AtomicReference<>();
            esStub.setInterceptor((req, defaultAction) -> {
                if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())) {
                    int call = aliasPostCounter.incrementAndGet();
                    if (call == 1) {
                        // Partial failure: old g1 binding removed, g2 not attached -> empty alias {}, fence still armed!
                        esStub.setAlias(ALIAS);
                        return StatefulElasticsearchStub.StubResponse.ok(
                                "{\"acknowledged\":false,\"errors\":true,\"items\":[{\"add\":{\"status\":500}}]}");
                    }
                    if (call == 2) {
                        // First fenced empty-alias repair times out in flight (capture raw payload, don't execute yet).
                        capturedLateRepairBody.set(req.body());
                        return StatefulElasticsearchStub.StubResponse.delayedStatus(
                                200, "{\"acknowledged\":true}", 1200L);
                    }
                }
                return defaultAction.get();
            });

            SearchRebuildService.StepOutcome partialOut = serviceB.advanceJob(job.jobId());
            assertThat(partialOut.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
            assertThat(esStub.aliasTargets(ALIAS)).isEmpty();

            SearchRebuildService.StepOutcome timedOutRepair = serviceB.advanceJob(job.jobId());
            assertThat(timedOutRepair.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
            assertThat(capturedLateRepairBody.get()).isNotNull();
            assertThat(esStub.aliasTargets(ALIAS)).isEmpty();
            assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                    .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);

            // 5) Clear ES fault: Instance B retries fenced empty-alias repair -> consumes g2__attach_fence and activates g2!
            esStub.clearInterceptor();
            RebuildJobRecord g2Completed = serviceB.runUntilTerminalOrLimit(job.jobId(), 10);
            assertThat(g2Completed.phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
            assertThat(esStub.aliasTargets(SearchIndexAdminClient.attachFenceAlias(G2))).isEmpty();

            // 6) Run subsequent g3 rebuild and switch active alias to {g3}!
            snapshotStub.setLivePublicArticles(List.of(
                    LoopbackContentSnapshotStub.snapshotItem(9102L, 2L, "G2TargetArticle", "Sum G2", "Body G2",
                            1L, "Tech", List.of("ES"), 10L, 1L, "2026-10-01T00:00:00Z"),
                    LoopbackContentSnapshotStub.snapshotItem(9103L, 3L, "G3FreshAuthorityArticle", "Sum G3", "Body G3",
                            1L, "Tech", List.of("ES"), 20L, 1L, "2026-10-02T00:00:00Z")
            ));
            visibilityTable.put("9103", new VisibilityDecision("9103", "3", "PUBLIC", "READABLE"));

            RebuildJobRecord g3Job = serviceB.startRebuild("req-r3-g3");
            assertThat(g3Job.candidatePhysicalIndex()).isEqualTo(G3);
            RebuildJobRecord g3Completed = serviceB.runUntilTerminalOrLimit(g3Job.jobId(), 20);
            assertThat(g3Completed.phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);
            assertThat(esStub.aliasTargets(SearchIndexAdminClient.attachFenceAlias(G2))).isEmpty();
            assertThat(countAliasPostsAddingFence(SearchIndexAdminClient.attachFenceAlias(G2))).isZero();

            // 7) Release expired Instance A from its paused ensurePhysicalIndex(G2) execution!
            releaseInstanceA.countDown();
            SearchRebuildService.StepOutcome staleOutcome = staleInitFuture.get(10, TimeUnit.SECONDS);
            assertThat(staleOutcome.status()).isEqualTo(SearchRebuildService.StepStatus.LEASE_LOST_FENCED);

            // CRITICAL: Expired Instance A MUST NOT have sent POST /_aliases to re-arm g2__attach_fence!
            assertThat(countAliasPostsAddingFence(SearchIndexAdminClient.attachFenceAlias(G2))).isZero();
            assertThat(esStub.aliasTargets(SearchIndexAdminClient.attachFenceAlias(G2))).isEmpty();

            // Also verify a delayed PUT /<g2> with inline aliases arriving after g3 cutover is rejected (400)
            // by ES without recreating g2__attach_fence.
            HttpClient rawHttp = HttpClient.newHttpClient();
            String createBody = "{\"aliases\":{\"" + SearchIndexAdminClient.attachFenceAlias(G2) + "\":{}}}";
            HttpResponse<String> latePutResp = rawHttp.send(
                    HttpRequest.newBuilder(URI.create(esStub.baseUrl() + "/" + G2))
                            .PUT(HttpRequest.BodyPublishers.ofString(createBody, StandardCharsets.UTF_8))
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(latePutResp.statusCode()).isEqualTo(400);
            assertThat(esStub.aliasTargets(SearchIndexAdminClient.attachFenceAlias(G2))).isEmpty();

            // 8) Replay the previously captured timed-out g2 empty-alias repair -> MUST be rejected (HTTP 404)!
            StatefulElasticsearchStub.StubResponse lateRepairResp =
                    esStub.applyUpdateAliasesBodyDirectly(capturedLateRepairBody.get());
            assertThat(lateRepairResp.status()).isEqualTo(404);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);

            IndexGenerationState finalGen = consumerRepository.findGenerationState(ALIAS).orElseThrow();
            assertThat(finalGen.activeGeneration()).isEqualTo(3L);
            assertThat(finalGen.activePhysicalIndex()).isEqualTo(G3);
            assertThat(finalGen.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);

            // Public search (zero consumer DB) serves g3 cleanly.
            PublicArticleSearchService searchService = createPublicSearchService();
            PageResult g3Hit = searchService.search("G3FreshAuthorityArticle", 1, 5, "relevance", "trace-r3-1");
            assertThat(g3Hit.rows()).hasSize(1);
            assertThat(g3Hit.rows().get(0).id()).isEqualTo(9103L);
        } finally {
            releaseInstanceA.countDown();
            pool.shutdownNow();
            esClientA.close();
        }
    }

    /**
     * SEARCH-03-R3 Regression 2:
     * 1) Warm stale empty-result cache on g1 for keyword "OnlyInG2RetryToken".
     * 2) Attempt 1 of g2 cutover on Instance A receives confirmed ES 4xx pre-switch rejection, commits
     *    local rollback, and PAUSES right before Redis cutover_bypass cleanup.
     * 3) Instance B takes over THE SAME job and THE SAME candidate g2, retries cutover (attempt 2),
     *    switches ES alias g1 -> g2, and encounters Redis INCR failure while GET/EVAL remain available.
     * 4) Release Instance A's delayed rollback cleanup -> attempt 2's cutover_bypass MUST survive,
     *    DB compensation MUST remain PENDING, and public search (zero consumer DB) MUST keep hitting g2.
     * 5) Restore INCR -> compensation advances epoch, clears ONLY attempt 2's bypass, and re-enables caching.
     */
    @Test
    void preventsDelayedPreSwitchRollbackCleanupFromDeletingRetryAttemptCutoverBypass() throws Exception {
        // 1) Warm stale empty-result cache on g1 (epoch 1) for "OnlyInG2RetryToken".
        PublicArticleSearchService publicSearch = createPublicSearchService();
        PageResult miss1 = publicSearch.search("OnlyInG2RetryToken", 1, 5, "relevance", "trace-warm-1");
        PageResult miss2 = publicSearch.search("OnlyInG2RetryToken", 1, 5, "relevance", "trace-warm-2");
        assertThat(miss1.rows()).isEmpty();
        assertThat(miss2.rows()).isEmpty();

        int esSearchesBeforeHit = countEsSearchRequests();
        PageResult cachedEmpty = publicSearch.search("OnlyInG2RetryToken", 1, 5, "relevance", "trace-warm-hit");
        assertThat(cachedEmpty.rows()).isEmpty();
        assertThat(countEsSearchRequests()).isEqualTo(esSearchesBeforeHit);

        // 2) Seed new article matching "OnlyInG2RetryToken" in 03 snapshot and prepare g2 rebuild up to SWITCHING_ALIAS (attempt 1).
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(9202L, 2L, "OnlyInG2RetryToken Published In G2",
                        "Sum G2", "Body G2", 1L, "Tech", List.of("ES"), 15L, 1L, "2026-10-01T00:00:00Z")
        ));
        visibilityTable.put("9202", new VisibilityDecision("9202", "2", "PUBLIC", "READABLE"));

        CountDownLatch rollbackPausedBeforeRedisClear = new CountDownLatch(1);
        CountDownLatch releaseRollbackCleanup = new CountDownLatch(1);
        AtomicReference<String> capturedAttempt1Owner = new AtomicReference<>();

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
                "inst-rollback-A",
                MAPPER,
                clock
        ) {
            @Override
            protected void beforeClearCutoverBypassAfterPreSwitchRevert(RebuildJobRecord job, String bypassOwner) {
                capturedAttempt1Owner.set(bypassOwner);
                rollbackPausedBeforeRedisClear.countDown();
                try {
                    assertThat(releaseRollbackCleanup.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        SearchRebuildService instanceB = createRebuildService("inst-retry-B");

        RebuildJobRecord job = instanceA.startRebuild("req-r3-retry-g2");
        RebuildJobRecord atAttempt1Switch = advanceUntilPhase(instanceA, job.jobId(), RebuildPhase.SWITCHING_ALIAS);
        assertThat(atAttempt1Switch.candidateGeneration()).isEqualTo(2L);
        assertThat(atAttempt1Switch.switchAttempts()).isEqualTo(1);

        // 3) Inject confirmed ES 4xx pre-switch rejection for Attempt 1.
        AtomicBoolean rejectFirstSwitch = new AtomicBoolean(true);
        esStub.setInterceptor((req, defaultAction) -> {
            if ("POST".equalsIgnoreCase(req.method())
                    && "/_aliases".equals(req.path())
                    && rejectFirstSwitch.compareAndSet(true, false)) {
                return StatefulElasticsearchStub.StubResponse.status(
                        400, "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"simulated_pre_switch_reject\"},\"status\":400}");
            }
            return defaultAction.get();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SearchRebuildService.StepOutcome> attempt1Future =
                    pool.submit(() -> instanceA.advanceJob(job.jobId()));
            assertThat(rollbackPausedBeforeRedisClear.await(5, TimeUnit.SECONDS)).isTrue();

            String expectedAttempt1Owner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 1L);
            assertThat(capturedAttempt1Owner.get()).isEqualTo(expectedAttempt1Owner);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedAttempt1Owner);

            RebuildJobRecord afterAttempt1Rollback = rebuildRepository.findJob(job.jobId()).orElseThrow();
            assertThat(afterAttempt1Rollback.phase()).isEqualTo(RebuildPhase.CUTOVER_PAUSING);
            assertThat(afterAttempt1Rollback.switchAttempts()).isEqualTo(1);
            assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                    .isEqualTo(IndexGenerationState.CutoverState.NORMAL);

            // 4) Instance B takes over THE SAME job & SAME candidate g2, retries switch (attempt 2),
            //    succeeds ES switch g1 -> g2, and encounters Redis INCR failure (GET/EVAL still available).
            esStub.clearInterceptor();
            clock.advance(Duration.ofSeconds(15));
            cacheRedis.failCommand("INCR", "ERR redis incr down on attempt 2");

            RebuildJobRecord atAttempt2CachePending =
                    advanceUntilPhase(instanceB, job.jobId(), RebuildPhase.CACHE_INVALIDATING);
            assertThat(atAttempt2CachePending.candidateGeneration()).isEqualTo(2L);
            assertThat(atAttempt2CachePending.switchAttempts()).isEqualTo(2);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);

            String expectedAttempt2Owner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 2L);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedAttempt2Owner);

            IndexGenerationState pendingBeforeRelease = consumerRepository.findGenerationState(ALIAS).orElseThrow();
            assertThat(pendingBeforeRelease.activeGeneration()).isEqualTo(2L);
            assertThat(pendingBeforeRelease.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);
            assertThat(pendingBeforeRelease.activeCutoverBypassOwner()).isEqualTo(expectedAttempt2Owner);

            // 5) Release Instance A's delayed rollback cleanup -> MUST NOT delete attempt 2's cutover_bypass!
            releaseRollbackCleanup.countDown();
            SearchRebuildService.StepOutcome attempt1Outcome = attempt1Future.get(10, TimeUnit.SECONDS);
            assertThat(attempt1Outcome.status()).isEqualTo(SearchRebuildService.StepStatus.PRE_SWITCH_FAILED_REVERTED);

            // CRITICAL ASSERTIONS: attempt 2's bypass & PENDING state intact; zero-DB public query keeps hitting g2!
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedAttempt2Owner);
            IndexGenerationState pendingAfterRelease = consumerRepository.findGenerationState(ALIAS).orElseThrow();
            assertThat(pendingAfterRelease.activeGeneration()).isEqualTo(2L);
            assertThat(pendingAfterRelease.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);
            assertThat(pendingAfterRelease.activeCutoverBypassOwner()).isEqualTo(expectedAttempt2Owner);

            PublicArticleSearchService freshRestartedPublicSearch = createPublicSearchService();
            PageResult bypassHit = freshRestartedPublicSearch.search(
                    "OnlyInG2RetryToken", 1, 5, "relevance", "trace-after-stale-rollback");
            assertThat(bypassHit.rows()).hasSize(1);
            assertThat(bypassHit.rows().get(0).id()).isEqualTo(9202L);

            // 6) Restore Redis INCR -> background worker compensates using persisted attempt-2 owner,
            //    advances epoch 1 -> 2, clears ONLY attempt 2's bypass, and re-enables caching on :e2!
            cacheRedis.clearFaults();
            clock.advance(Duration.ofSeconds(2));
            SearchIndexTaskWorker.CacheCompensationSummary comp = indexWorker.processPendingCacheInvalidations();
            assertThat(comp.completedCount()).isEqualTo(1);
            assertThat(comp.failedCount()).isZero();

            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();

            IndexGenerationState doneGen = consumerRepository.findGenerationState(ALIAS).orElseThrow();
            assertThat(doneGen.activeGeneration()).isEqualTo(2L);
            assertThat(doneGen.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
            assertThat(doneGen.activeCutoverBypassOwner()).isNull();

            // Complete rebuild job on Instance B cleanly.
            RebuildJobRecord completed = instanceB.runUntilTerminalOrLimit(job.jobId(), 5);
            assertThat(completed.phase()).isEqualTo(RebuildPhase.COMPLETED);

            // Re-enabled cache on epoch 2 caches g2 result after threshold and serves from Redis.
            PageResult e2First = freshRestartedPublicSearch.search("OnlyInG2RetryToken", 1, 5, "relevance", "trace-e2-1");
            PageResult e2Second = freshRestartedPublicSearch.search("OnlyInG2RetryToken", 1, 5, "relevance", "trace-e2-2");
            assertThat(e2First.rows()).hasSize(1);
            assertThat(e2Second.rows()).hasSize(1);
            int esBeforeE2Hit = countEsSearchRequests();
            PageResult e2Cached = freshRestartedPublicSearch.search("OnlyInG2RetryToken", 1, 5, "relevance", "trace-e2-3");
            assertThat(e2Cached.rows()).hasSize(1);
            assertThat(e2Cached.rows().get(0).id()).isEqualTo(9202L);
            assertThat(countEsSearchRequests()).isEqualTo(esBeforeE2Hit);
        } finally {
            releaseRollbackCleanup.countDown();
            pool.shutdownNow();
        }
    }

    // === Helpers ===

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

    private int countAliasPostsAddingFence(String fenceAlias) throws Exception {
        int count = 0;
        for (StatefulElasticsearchStub.RecordedRequest req : esStub.requests()) {
            if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())) {
                JsonNode root = MAPPER.readTree(req.body());
                JsonNode actions = root.path("actions");
                if (actions.isArray()) {
                    for (JsonNode action : actions) {
                        JsonNode add = action.get("add");
                        if (add != null && fenceAlias.equals(add.path("alias").asText())) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
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
