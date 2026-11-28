package com.knode.search.rebuild;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.knode.search.consumer.persistence.ContentEventIngestionService;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.PageResult;
import com.knode.search.model.QueryBudget;
import com.knode.search.rebuild.client.HttpContentSnapshotClient;
import com.knode.search.rebuild.config.SearchRebuildProperties;
import com.knode.search.rebuild.es.AliasSwitchResult;
import com.knode.search.rebuild.es.ElasticsearchIndexAdminClient;
import com.knode.search.rebuild.es.SearchIndexAdminClient;
import com.knode.search.rebuild.es.SearchVisibleDoc;
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

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchRebuildR9RegressionsTest {
    private static final String ENV = "it-rebuild-r9";
    private static final String ALIAS = "knode_it_r9_articles_search";
    private static final String G1 = ALIAS + "_g1";
    private static final String G2 = ALIAS + "_g2";
    private static final String G2_FENCE = SearchIndexAdminClient.attachFenceAlias(G2);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MutableClock clock;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private SearchConsumerRepository consumerRepository;
    private SearchRebuildRepository rebuildRepository;

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
    private SearchIndexTaskWorker indexTaskWorker;
    private ContentVisibilityClient visibilityClient;
    private Map<String, VisibilityDecision> visibilityTable;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-11-28T08:00:00Z"));
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:h2:mem:search_rebuild_r9_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        hikari.setUsername("sa");
        hikari.setPassword("");
        hikari.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(hikari);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("db/search-consumer-schema.sql"));
        populator.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        consumerRepository = new SearchConsumerRepository(jdbcTemplate, MAPPER);
        rebuildRepository = new SearchRebuildRepository(jdbcTemplate, consumerRepository, MAPPER);

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
        esProps.setResponseTimeoutMs(1500);

        queryProps = new SearchQueryProperties();
        queryProps.setTotalTimeoutMs(2500);

        SearchRuntimeConfig runtimeConfig = new SearchRuntimeConfig();
        esClient = runtimeConfig.elasticsearchClient(esProps, queryProps);

        snapshotStub = new LoopbackContentSnapshotStub();
        snapshotStub.setExpectedBearerToken("rb-r9-token");

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
        rebuildProps.setInstanceId("rb-r9-inst-1");
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
                return "rb-r9-token";
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
        indexTaskWorker = new SearchIndexTaskWorker(
                consumerRepository, transactionTemplate, indexWriter, cacheInvalidator, consumerProps, ALIAS, clock);
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

    // =========================================================================
    // Scenario 1: Crash AFTER committing alias_switch_uncertain=1 in SQL and
    // BEFORE first ES switch request produces any side effect -> takeover via
    // normal polling entry re-sends fenced switch & routes subsequent index update to G2!
    // =========================================================================
    @Test
    void recoversFromCrashAfterCommitUncertainBeforeEsSwitchViaPollingTakeoverAndRoutesSubsequentUpdatesToNewActiveIndex()
            throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(9901L, 1L, "R9 Scenario1 Baseline", "Sum", "Body",
                        1L, "Tech", List.of("ES"), 15L, 1L, "2026-11-28T00:00:00Z")
        ));
        visibilityTable.put("9901", new VisibilityDecision("9901", "1", "PUBLIC", "READABLE"));

        // Worker 1 crashes right AFTER SQL commits alias_switch_uncertain=1 and BEFORE ES _aliases executes!
        AtomicBoolean crashedBeforeEs = new AtomicBoolean(false);
        SearchIndexAdminClient crashingAdminClient = new DelegatingIndexAdminClient(indexAdminClient) {
            @Override
            public AliasSwitchResult switchAliasAtomicallyWithFence(
                    String aliasName, Set<String> oldPhysicalIndices, String newPhysicalIndex, String fenceAliasName) {
                RebuildJobRecord persisted = rebuildRepository.findActiveJob(ALIAS).orElseThrow();
                assertThat(persisted.phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
                assertThat(persisted.aliasSwitchUncertain()).isTrue();
                crashedBeforeEs.set(true);
                throw new SimulatedWorkerCrashException("worker-1 crashed after committing uncertain before ES send");
            }
        };

        SearchRebuildService worker1 = createRebuildService("rb-r9-inst-1", crashingAdminClient);
        RebuildJobRecord job = driveToSwitchingAlias(worker1, "req-r9-s1");
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);
        assertThat(esStub.aliasTargets(G2_FENCE)).containsExactly(G2);

        long aliasPostsBeforeCrash = countSwitchAliasPosts();
        assertThatThrownBy(() -> worker1.advanceJob(job.jobId()))
                .isInstanceOf(SimulatedWorkerCrashException.class);
        assertThat(crashedBeforeEs.get()).isTrue();

        // Verify post-crash state: SQL committed alias_switch_uncertain=1, ES STILL points solely to G1,
        // one-shot attach fence STILL present on G2, ZERO ES switch writes executed!
        RebuildJobRecord afterCrash = rebuildRepository.findJob(job.jobId()).orElseThrow();
        assertThat(afterCrash.phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(afterCrash.aliasSwitchUncertain()).isTrue();
        assertThat(afterCrash.switchAttempts()).isEqualTo(1);
        assertThat(afterCrash.leaseOwner()).isEqualTo("rb-r9-inst-1");
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);
        assertThat(esStub.aliasTargets(G2_FENCE)).containsExactly(G2);
        assertThat(countSwitchAliasPosts()).isEqualTo(aliasPostsBeforeCrash);
        String expectedBypassOwner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 1L);
        assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedBypassOwner);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);

        // Expire Worker 1's lease and let Worker 2 take over the SAME job through normal polling entry advanceActiveJob()!
        clock.advance(Duration.ofSeconds(15));
        SearchRebuildService worker2 = createRebuildService("rb-r9-inst-2", indexAdminClient);
        SearchRebuildService.StepOutcome recovered = worker2.advanceActiveJob();

        assertThat(recovered.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        assertThat(recovered.job()).isNotNull();
        assertThat(recovered.job().jobId()).isEqualTo(job.jobId());
        assertThat(recovered.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(recovered.job().aliasSwitchUncertain()).isFalse();
        assertThat(recovered.job().switchAttempts()).isEqualTo(1);
        assertThat(recovered.job().leaseOwner()).isEqualTo("rb-r9-inst-2");

        // Verify ES alias uniquely points to G2, attach fence consumed, active_generation=2, cutover_state=NORMAL,
        // cutover_bypass cleared and watermark 2:1:CLEARED!
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(esStub.aliasTargets(G2_FENCE)).isEmpty();
        assertThat(countSwitchAliasPosts()).isEqualTo(aliasPostsBeforeCrash + 1L);

        IndexGenerationState genAfter = consumerRepository.findGenerationState(ALIAS).orElseThrow();
        assertThat(genAfter.activeGeneration()).isEqualTo(2L);
        assertThat(genAfter.activePhysicalIndex()).isEqualTo(G2);
        assertThat(genAfter.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);
        assertThat(genAfter.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
        assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
        assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:CLEARED");
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");

        // Verify a subsequent live article update writes to the NEW active physical index G2 (not G1)
        // and becomes searchable via public search!
        visibilityTable.put("9902", new VisibilityDecision("9902", "1", "PUBLIC", "READABLE"));
        ContentEventIngestionService ingestionService = new ContentEventIngestionService(
                eventParser, consumerRepository, transactionTemplate, consumerProps, ALIAS, visibilityClient, MAPPER, clock);
        ContentEventIngestionService.IngestionOutcome ingested = ingestionService.ingestStreamMessage(
                "1700000001000-0",
                buildArticlePublishedStreamFields("evt-r9-s1-post-9902", 9902L, 1L, "R9 PostCutover LiveG2Doc"));
        assertThat(ingested).isEqualTo(ContentEventIngestionService.IngestionOutcome.APPLIED);

        SearchIndexTaskRecord taskOnG2 = consumerRepository.findIndexTask(2L, 9902L).orElseThrow();
        assertThat(taskOnG2.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(consumerRepository.findIndexTask(1L, 9902L)).isEmpty();

        SearchIndexTaskWorker.WorkerCycleSummary summary = indexTaskWorker.processOnce();
        assertThat(summary.appliedCount()).isEqualTo(1);
        StatefulElasticsearchStub.StoredDoc writtenG2 = esStub.getDocument(G2, "9902");
        assertThat(writtenG2).isNotNull();
        assertThat(MAPPER.readTree(writtenG2.sourceJson()).path("title").asText())
                .isEqualTo("R9 PostCutover LiveG2Doc");
        assertThat(esStub.getDocument(G1, "9902")).isNull();

        PublicArticleSearchService publicSearch = createPublicSearchService();
        PageResult hit = publicSearch.search("LiveG2Doc", 1, 5, "relevance", "q-r9-s1-post");
        assertThat(hit.rows()).hasSize(1);
        assertThat(hit.rows().get(0).id()).isEqualTo(9902L);
    }

    // =========================================================================
    // Scenario 2: ES switch SUCCEEDED and consumed attach fence, Worker 1 crashed
    // before local SQL activation -> Worker 2 reconciles purely by reading ES
    // with ZERO extra ES switch write requests!
    // =========================================================================
    @Test
    void reconcilesForwardWithoutExtraSwitchWriteWhenEsSwitchedAndConsumedFenceBeforeLocalCommit() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(9911L, 1L, "R9 Scenario2 ReceiptLost", "Sum", "Body",
                        1L, "Tech", List.of("ES"), 20L, 1L, "2026-11-28T00:00:00Z")
        ));
        visibilityTable.put("9911", new VisibilityDecision("9911", "1", "PUBLIC", "READABLE"));

        SearchRebuildService worker1 = new SearchRebuildService(
                rebuildRepository, consumerRepository, transactionTemplate, snapshotClient, eventParser,
                indexTaskWorker, indexAdminClient, cacheInvalidator, consumerProps, rebuildProps,
                ALIAS, "rb-r9-s2-inst-1", MAPPER, clock) {
            @Override
            protected void afterAtomicAliasSwitchBeforeLocalCommit(RebuildJobRecord job) {
                throw new SimulatedWorkerCrashException("worker-1 crashed after ES switch before local commit");
            }
        };

        RebuildJobRecord job = driveToSwitchingAlias(worker1, "req-r9-s2");
        long postsBeforeSwitch = countSwitchAliasPosts();

        assertThatThrownBy(() -> worker1.advanceJob(job.jobId()))
                .isInstanceOf(SimulatedWorkerCrashException.class);

        // ES already switched {G1} -> {G2} and consumed G2_FENCE; local DB still in SWITCHING_ALIAS with uncertain=1.
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(esStub.aliasTargets(G2_FENCE)).isEmpty();
        assertThat(countSwitchAliasPosts()).isEqualTo(postsBeforeSwitch + 1L);
        RebuildJobRecord afterCrash = rebuildRepository.findJob(job.jobId()).orElseThrow();
        assertThat(afterCrash.phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(afterCrash.aliasSwitchUncertain()).isTrue();
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(1L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);

        // Lease expires -> Worker 2 takes over SAME job via advanceActiveJob() -> reconciles purely by read!
        clock.advance(Duration.ofSeconds(15));
        SearchRebuildService worker2 = createRebuildService("rb-r9-s2-inst-2", indexAdminClient);
        SearchRebuildService.StepOutcome reconciled = worker2.advanceActiveJob();

        assertThat(reconciled.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        assertThat(reconciled.job().jobId()).isEqualTo(job.jobId());
        assertThat(reconciled.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(reconciled.job().aliasSwitchUncertain()).isFalse();
        assertThat(reconciled.job().switchAttempts()).isEqualTo(1);

        // ZERO extra ES switch write requests during reconciliation (total switch writes == 1)!
        assertThat(countSwitchAliasPosts()).isEqualTo(postsBeforeSwitch + 1L);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(esStub.aliasTargets(G2_FENCE)).isEmpty();

        IndexGenerationState finalGen = consumerRepository.findGenerationState(ALIAS).orElseThrow();
        assertThat(finalGen.activeGeneration()).isEqualTo(2L);
        assertThat(finalGen.activePhysicalIndex()).isEqualTo(G2);
        assertThat(finalGen.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);
        assertThat(finalGen.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
        assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
        assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:CLEARED");
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
    }

    // =========================================================================
    // Scenario 3: Original ES _aliases request pauses in flight until takeover
    // Worker 2's re-sent request succeeds, then original request finally reaches ES!
    // Assert: at most 1 of 2 succeeds (late original gets HTTP 404 via must_exist=true),
    // alias stays solely {G2}, old lease fenced (LEASE_LOST_FENCED), bypass cleaned by owner!
    // =========================================================================
    @Test
    void rejectsLateOriginalSwitchAfterTakeoverResendSucceedsFencesStaleLeaseAndCleansBypassByOwner() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(9921L, 1L, "R9 Scenario3 LateOriginal", "Sum", "Body",
                        1L, "Tech", List.of("ES"), 25L, 1L, "2026-11-28T00:00:00Z")
        ));
        visibilityTable.put("9921", new VisibilityDecision("9921", "1", "PUBLIC", "READABLE"));

        // Give Worker 1 a longer ES response timeout so its paused HTTP request stays genuinely in flight
        // until Worker 2 finishes and we release the latch!
        SearchElasticsearchProperties worker1EsProps = new SearchElasticsearchProperties();
        worker1EsProps.setUrl(esStub.baseUrl());
        worker1EsProps.setIndex(ALIAS);
        worker1EsProps.setConnectTimeoutMs(500);
        worker1EsProps.setConnectionRequestTimeoutMs(500);
        worker1EsProps.setResponseTimeoutMs(10_000);
        ElasticsearchClient worker1EsClient = new SearchRuntimeConfig().elasticsearchClient(worker1EsProps, queryProps);
        ElasticsearchIndexAdminClient worker1AdminClient = new ElasticsearchIndexAdminClient(worker1EsClient, MAPPER);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            SearchRebuildService worker1 = createRebuildService("rb-r9-s3-inst-1", worker1AdminClient);
            RebuildJobRecord job = driveToSwitchingAlias(worker1, "req-r9-s3");

            CountDownLatch originalInFlightPaused = new CountDownLatch(1);
            CountDownLatch releaseOriginal = new CountDownLatch(1);
            AtomicInteger aliasPostSeq = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger originalHttpStatus = new AtomicInteger(0);
            AtomicInteger resendHttpStatus = new AtomicInteger(0);
            AtomicReference<String> originalPayload = new AtomicReference<>();
            AtomicReference<String> resendPayload = new AtomicReference<>();

            esStub.setInterceptor((req, def) -> {
                if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())) {
                    int seq = aliasPostSeq.incrementAndGet();
                    if (seq == 1) {
                        originalPayload.set(req.body());
                        originalInFlightPaused.countDown();
                        try {
                            assertThat(releaseOriginal.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        StatefulElasticsearchStub.StubResponse resp = def.get();
                        originalHttpStatus.set(resp.status());
                        if (resp.status() >= 200 && resp.status() < 300) {
                            successCount.incrementAndGet();
                        }
                        return resp;
                    }
                    if (seq == 2) {
                        resendPayload.set(req.body());
                        StatefulElasticsearchStub.StubResponse resp = def.get();
                        resendHttpStatus.set(resp.status());
                        if (resp.status() >= 200 && resp.status() < 300) {
                            successCount.incrementAndGet();
                        }
                        return resp;
                    }
                }
                return def.get();
            });

            // 1) Launch Worker 1; it commits alias_switch_uncertain=1, sends POST /_aliases, and pauses BEFORE ES applies it.
            Future<SearchRebuildService.StepOutcome> worker1Future = pool.submit(() -> worker1.advanceJob(job.jobId()));
            assertThat(originalInFlightPaused.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(rebuildRepository.findJob(job.jobId()).orElseThrow().aliasSwitchUncertain()).isTrue();
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);
            assertThat(esStub.aliasTargets(G2_FENCE)).containsExactly(G2);

            // 2) Lease expires -> Worker 2 takes over via advanceActiveJob(), re-sends fenced POST /_aliases -> SUCCEEDS!
            clock.advance(Duration.ofSeconds(15));
            SearchRebuildService worker2 = createRebuildService("rb-r9-s3-inst-2", indexAdminClient);
            SearchRebuildService.StepOutcome worker2Outcome = worker2.advanceActiveJob();

            assertThat(worker2Outcome.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
            assertThat(worker2Outcome.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(worker2Outcome.job().switchAttempts()).isEqualTo(1);
            assertThat(resendHttpStatus.get()).isEqualTo(200);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
            assertThat(esStub.aliasTargets(G2_FENCE)).isEmpty();
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:CLEARED");

            // Verify both requests carried identical atomic must_exist=true actions (remove source + add candidate + remove fence).
            JsonNode origActions = MAPPER.readTree(originalPayload.get()).path("actions");
            JsonNode resendActions = MAPPER.readTree(resendPayload.get()).path("actions");
            assertThat(origActions).isEqualTo(resendActions);
            assertThat(resendActions).hasSize(3);
            assertThat(resendActions.get(0).path("remove").path("index").asText()).isEqualTo(G1);
            assertThat(resendActions.get(0).path("remove").path("must_exist").asBoolean()).isTrue();
            assertThat(resendActions.get(1).path("add").path("index").asText()).isEqualTo(G2);
            assertThat(resendActions.get(2).path("remove").path("alias").asText()).isEqualTo(G2_FENCE);
            assertThat(resendActions.get(2).path("remove").path("must_exist").asBoolean()).isTrue();

            // 3) Release Worker 1's paused original POST /_aliases so it actually reaches ES AFTER Worker 2 completed!
            releaseOriginal.countDown();
            SearchRebuildService.StepOutcome worker1Outcome = worker1Future.get(10, TimeUnit.SECONDS);

            // At most ONE of the two ES requests succeeded; late original was atomically rejected with HTTP 404!
            assertThat(aliasPostSeq.get()).isEqualTo(2);
            assertThat(originalHttpStatus.get()).isEqualTo(404);
            assertThat(successCount.get()).isEqualTo(1);

            // Worker 1 was strictly lease-fenced on local writeback and could NOT mutate SQL or cutover_bypass!
            assertThat(worker1Outcome.status()).isEqualTo(SearchRebuildService.StepStatus.LEASE_LOST_FENCED);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
            assertThat(esStub.aliasTargets(G2_FENCE)).isEmpty();

            RebuildJobRecord finalJob = rebuildRepository.findJob(job.jobId()).orElseThrow();
            assertThat(finalJob.phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(finalJob.leaseOwner()).isEqualTo("rb-r9-s3-inst-2");
            IndexGenerationState finalGen = consumerRepository.findGenerationState(ALIAS).orElseThrow();
            assertThat(finalGen.activeGeneration()).isEqualTo(2L);
            assertThat(finalGen.activePhysicalIndex()).isEqualTo(G2);
            assertThat(finalGen.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:CLEARED");
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");
        } finally {
            pool.shutdownNow();
            worker1EsClient.close();
        }
    }

    // =========================================================================
    // Edge Case 4A: Re-sent switch is rejected (HTTP 400 / 404) while original
    // request is still pending -> MUST NOT revert job, MUST NOT unpause cutover,
    // MUST NOT clear cutover_bypass; stays in SWITCHING_ALIAS until ES confirms!
    // =========================================================================
    @Test
    void holdsSwitchingAliasAndPreservesBypassWhenResendIsRejectedWhileOriginalRequestIsStillPending() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(9931L, 1L, "R9 ResendRejectedStillPending", "Sum", "Body",
                        1L, "Tech", List.of("ES"), 30L, 1L, "2026-11-28T00:00:00Z")
        ));
        visibilityTable.put("9931", new VisibilityDecision("9931", "1", "PUBLIC", "READABLE"));

        SearchRebuildService worker1 = createRebuildService("rb-r9-4a-inst-1", indexAdminClient);
        RebuildJobRecord job = driveToSwitchingAlias(worker1, "req-r9-4a");

        // 1) Worker 1's original POST /_aliases times out (captured, still pending, not yet applied on ES).
        AtomicReference<String> capturedOriginalPayload = new AtomicReference<>();
        AtomicInteger postSeq = new AtomicInteger(0);
        esStub.setInterceptor((req, def) -> {
            if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())) {
                int seq = postSeq.incrementAndGet();
                if (seq == 1) {
                    capturedOriginalPayload.set(req.body());
                    return StatefulElasticsearchStub.StubResponse.delayedStatus(200, "{\"acknowledged\":true}", 2200L);
                }
                if (seq == 2) {
                    // Worker 2's re-send receives a synchronous HTTP 400 rejection while original is STILL pending!
                    return StatefulElasticsearchStub.StubResponse.status(
                            400, "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"injected_resend_reject\"}}");
                }
            }
            return def.get();
        });

        SearchRebuildService.StepOutcome timedOut = worker1.advanceJob(job.jobId());
        assertThat(timedOut.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(timedOut.job().aliasSwitchUncertain()).isTrue();
        assertThat(capturedOriginalPayload.get()).isNotNull();

        // 2) Lease expires -> Worker 2 takes over via advanceActiveJob(), re-send gets HTTP 400!
        //    MUST NOT revert job to CUTOVER_PAUSING/FAILED, MUST NOT unpause cutover_state, MUST NOT clear bypass!
        clock.advance(Duration.ofSeconds(15));
        SearchRebuildService worker2 = createRebuildService("rb-r9-4a-inst-2", indexAdminClient);
        SearchRebuildService.StepOutcome resendRejected = worker2.advanceActiveJob();

        assertThat(resendRejected.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(resendRejected.job().phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(resendRejected.job().aliasSwitchUncertain()).isTrue();
        assertThat(resendRejected.job().lastError()).contains("ALIAS_UNCERTAIN_RESEND_CONFIRMED_PRE_SWITCH_REJECTED");
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);
        assertThat(esStub.aliasTargets(G2_FENCE)).containsExactly(G2);

        IndexGenerationState genStillGated = consumerRepository.findGenerationState(ALIAS).orElseThrow();
        assertThat(genStillGated.activeGeneration()).isEqualTo(1L);
        assertThat(genStillGated.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);
        String expectedBypassOwner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 1L);
        assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedBypassOwner);
        assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassWatermarkKey())).isEqualTo("2:1:ACTIVE");

        // 3) Now the pending original POST /_aliases finally lands on ES -> next poll reconciles forward cleanly!
        esStub.clearInterceptor();
        StatefulElasticsearchStub.StubResponse lateOriginalResp =
                esStub.applyUpdateAliasesBodyDirectly(capturedOriginalPayload.get());
        assertThat(lateOriginalResp.status()).isEqualTo(200);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(esStub.aliasTargets(G2_FENCE)).isEmpty();

        SearchRebuildService.StepOutcome reconciled = worker2.advanceActiveJob();
        assertThat(reconciled.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        assertThat(reconciled.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(reconciled.job().aliasSwitchUncertain()).isFalse();
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(2L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.NORMAL);
        assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
    }

    // =========================================================================
    // Edge Case 4B: Fence missing when alias still points to source OR alias
    // points to unexpected index -> safe reconciliation hold, never re-arm fence,
    // never revert/unpause/clear bypass, record diagnosable last_error!
    // =========================================================================
    @Test
    void holdsSafeReconciliationWithoutReArmingFenceWhenFenceMissingOrAliasPointsToUnexpectedIndex() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(9941L, 1L, "R9 FenceMissingOrUnexpected", "Sum", "Body",
                        1L, "Tech", List.of("ES"), 35L, 1L, "2026-11-28T00:00:00Z")
        ));
        visibilityTable.put("9941", new VisibilityDecision("9941", "1", "PUBLIC", "READABLE"));

        // Drive to SWITCHING_ALIAS with alias_switch_uncertain=1 via crash before ES send.
        SearchIndexAdminClient crashingAdminClient = new DelegatingIndexAdminClient(indexAdminClient) {
            @Override
            public AliasSwitchResult switchAliasAtomicallyWithFence(
                    String aliasName, Set<String> oldPhysicalIndices, String newPhysicalIndex, String fenceAliasName) {
                throw new SimulatedWorkerCrashException("crash after committing uncertain");
            }
        };
        SearchRebuildService worker1 = createRebuildService("rb-r9-4b-inst-1", crashingAdminClient);
        RebuildJobRecord job = driveToSwitchingAlias(worker1, "req-r9-4b");
        assertThatThrownBy(() -> worker1.advanceJob(job.jobId()))
                .isInstanceOf(SimulatedWorkerCrashException.class);

        String expectedBypassOwner = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L, 1L);
        clock.advance(Duration.ofSeconds(15));
        SearchRebuildService worker2 = createRebuildService("rb-r9-4b-inst-2", indexAdminClient);

        // Part 1: Alias still points solely to G1, but candidate attach fence is already missing/consumed!
        esStub.setAlias(G2_FENCE);
        long postsBeforeMissingFence = countAllAliasesPosts();

        SearchRebuildService.StepOutcome missingFenceOut = worker2.advanceActiveJob();
        assertThat(missingFenceOut.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(missingFenceOut.job().phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(missingFenceOut.job().aliasSwitchUncertain()).isTrue();
        assertThat(missingFenceOut.job().lastError()).contains("ALIAS_UNCERTAIN_RESEND_FENCE_MISSING");
        // Fence MUST stay consumed (NEVER re-armed!), zero POST /_aliases emitted, gate & bypass preserved!
        assertThat(esStub.aliasTargets(G2_FENCE)).isEmpty();
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);
        assertThat(countAllAliasesPosts()).isEqualTo(postsBeforeMissingFence);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(1L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);
        assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedBypassOwner);

        // Part 2: Alias points to an unexpected index (neither source, candidate, nor empty)!
        String unexpectedIdx = ALIAS + "_unexpected_external_g99";
        esStub.setAlias(ALIAS, unexpectedIdx);
        long postsBeforeUnexpected = countAllAliasesPosts();

        SearchRebuildService.StepOutcome unexpectedOut = worker2.advanceActiveJob();
        assertThat(unexpectedOut.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(unexpectedOut.job().phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(unexpectedOut.job().aliasSwitchUncertain()).isTrue();
        assertThat(unexpectedOut.job().lastError()).contains("ALIAS_SWITCH_UNEXPECTED_TARGETS_UNCERTAIN");
        assertThat(unexpectedOut.job().lastError()).contains(unexpectedIdx);
        // ES untouched, zero POST /_aliases emitted, active_generation=1, cutover_state=SWITCHING_ALIAS, bypass preserved!
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(unexpectedIdx);
        assertThat(esStub.aliasTargets(G2_FENCE)).isEmpty();
        assertThat(countAllAliasesPosts()).isEqualTo(postsBeforeUnexpected);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(1L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);
        assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(expectedBypassOwner);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SearchRebuildService createRebuildService(String instanceId, SearchIndexAdminClient adminClient) {
        return new SearchRebuildService(
                rebuildRepository,
                consumerRepository,
                transactionTemplate,
                snapshotClient,
                eventParser,
                indexTaskWorker,
                adminClient,
                cacheInvalidator,
                consumerProps,
                rebuildProps,
                ALIAS,
                instanceId,
                MAPPER,
                clock
        );
    }

    private RebuildJobRecord driveToSwitchingAlias(SearchRebuildService service, String clientReqId) {
        RebuildJobRecord job = service.startRebuild(clientReqId);
        for (int i = 0; i < 20; i++) {
            RebuildJobRecord cur = rebuildRepository.findJob(job.jobId()).orElseThrow();
            if (cur.phase() == RebuildPhase.SWITCHING_ALIAS) {
                return cur;
            }
            service.advanceJob(job.jobId());
        }
        throw new AssertionError("Did not reach SWITCHING_ALIAS for job " + job.jobId());
    }

    private long countSwitchAliasPosts() {
        return esStub.requests().stream()
                .filter(r -> "POST".equalsIgnoreCase(r.method()) && "/_aliases".equals(r.path()))
                .filter(r -> r.body() != null && r.body().contains("\"" + ALIAS + "\""))
                .count();
    }

    private long countAllAliasesPosts() {
        return esStub.requests().stream()
                .filter(r -> "POST".equalsIgnoreCase(r.method()) && "/_aliases".equals(r.path()))
                .count();
    }

    private Map<String, String> buildArticlePublishedStreamFields(
            String eventId, long articleId, long revision, String title) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", "ArticlePublished");
        root.put("schemaVersion", 2);
        root.put("producer", "knode-content");
        root.put("occurredAt", "2026-11-28T08:05:00Z");
        root.put("aggregateType", "ARTICLE");
        root.put("visibility", "PUBLIC");
        root.put("publicationFactId", "pub-" + articleId + "-v" + revision);
        root.put("aggregateId", Long.toString(articleId));
        root.put("sourceRevision", Long.toString(revision));
        ObjectNode payload = root.putObject("payload");
        payload.put("articleId", Long.toString(articleId));
        payload.put("authorId", "9001");
        payload.put("title", title);
        payload.put("summary", "Post cutover summary");
        payload.put("content", "Post cutover body for " + title);
        payload.put("thumbnail", "https://cdn.example.com/" + articleId + ".png");
        payload.put("isTop", "0");
        payload.put("isComment", "1");
        ObjectNode cat = payload.putObject("category");
        cat.put("id", "10");
        cat.put("name", "Tech");
        ArrayNode tags = payload.putArray("tags");
        ObjectNode tag = tags.addObject();
        tag.put("id", "100");
        tag.put("name", "R9");
        payload.put("createTime", "2026-11-28T08:05:00Z");
        payload.put("publishedAt", "2026-11-28T08:05:00Z");
        payload.put("viewCount", "5");
        payload.put("counterRevision", "1");
        return Map.of("event", MAPPER.writeValueAsString(root));
    }

    private PublicArticleSearchService createPublicSearchService() {
        SearchMetrics metrics = new SearchMetrics(new SimpleMeterRegistry());
        ObjectProvider<StringRedisTemplate> cacheProvider = singleObjectProvider(cacheRedisTemplate);
        ArticleSearchCache cache = new ArticleSearchCache(cacheProvider, MAPPER, cacheProps, metrics, ENV);
        SearchQueryPlanner planner = new SearchQueryPlanner(queryProps, cacheProps, esProps, ENV);
        ElasticsearchArticleSearchRepository searchRepo = new ElasticsearchArticleSearchRepository(esClient, esProps);
        return new PublicArticleSearchService(planner, cache, searchRepo, visibilityClient, queryProps, metrics);
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

    private static final class SimulatedWorkerCrashException extends RuntimeException {
        SimulatedWorkerCrashException(String message) {
            super(message);
        }
    }

    private static abstract class DelegatingIndexAdminClient implements SearchIndexAdminClient {
        private final SearchIndexAdminClient delegate;

        DelegatingIndexAdminClient(SearchIndexAdminClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public void ensurePhysicalIndex(String physicalIndex) {
            delegate.ensurePhysicalIndex(physicalIndex);
        }

        @Override
        public void refreshIndex(String physicalIndex) {
            delegate.refreshIndex(physicalIndex);
        }

        @Override
        public long countDocumentsBySearchable(String physicalIndex, boolean searchable) {
            return delegate.countDocumentsBySearchable(physicalIndex, searchable);
        }

        @Override
        public Map<String, SearchVisibleDoc> fetchSearchVisibleDocsByIds(String physicalIndex, List<String> docIds) {
            return delegate.fetchSearchVisibleDocsByIds(physicalIndex, docIds);
        }

        @Override
        public Set<String> getAliasTargets(String aliasName) {
            return delegate.getAliasTargets(aliasName);
        }

        @Override
        public AliasSwitchResult switchAliasAtomically(
                String aliasName, Set<String> removeFromIndices, String targetPhysicalIndex) {
            return delegate.switchAliasAtomically(aliasName, removeFromIndices, targetPhysicalIndex);
        }

        @Override
        public AliasSwitchResult switchAliasAtomicallyWithFence(
                String aliasName, Set<String> removeFromIndices, String targetPhysicalIndex, String attachFenceAlias) {
            return delegate.switchAliasAtomicallyWithFence(aliasName, removeFromIndices, targetPhysicalIndex, attachFenceAlias);
        }

        @Override
        public AliasSwitchResult removeAliasBindingsAtomically(String aliasName, Set<String> removeFromIndices) {
            return delegate.removeAliasBindingsAtomically(aliasName, removeFromIndices);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant start) {
            this.current = new AtomicReference<>(start);
        }

        void advance(Duration delta) {
            current.updateAndGet(cur -> cur.plus(delta));
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
            return current.get();
        }
    }
}
