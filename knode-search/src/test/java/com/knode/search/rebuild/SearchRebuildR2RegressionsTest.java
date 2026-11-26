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
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.event.ContentEventParser;
import com.knode.search.consumer.indexer.ElasticsearchArticleIndexWriter;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.ArticleVersionBarrier;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.ContentEventIngestionService;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.stream.ContentStreamConsumer;
import com.knode.search.consumer.stream.LettuceContentStreamClient;
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

import java.net.http.HttpClient;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRebuildR2RegressionsTest {
    private static final String ENV = "it-rebuild-r2";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String GROUP = "knode-search-v1";
    private static final String ALIAS = "knode_it_r2_articles_search";
    private static final String G1 = ALIAS + "_g1";
    private static final String G2 = ALIAS + "_g2";
    private static final String G3 = ALIAS + "_g3";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate txTemplate;
    private MutableClock clock;
    private LoopbackRedisStub streamRedis;
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
    private ContentEventIngestionService ingestionService;
    private LettuceContentStreamClient streamClient;
    private ContentStreamConsumer streamConsumer;
    private ElasticsearchArticleIndexWriter indexWriter;
    private SearchCacheInvalidator cacheInvalidator;
    private SearchIndexTaskWorker indexWorker;
    private HttpContentSnapshotClient snapshotClient;
    private ElasticsearchIndexAdminClient indexAdminClient;
    private SearchRebuildService rebuildService;
    private ContentVisibilityClient visibilityClient;
    private Map<String, VisibilityDecision> visibilityTable;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "search_rb_r2_" + UUID.randomUUID().toString().replace("-", "");
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

        clock = new MutableClock(Instant.parse("2026-11-26T12:00:00Z"));
        streamRedis = new LoopbackRedisStub();
        streamRedis.createGroup(STREAM_KEY, GROUP);

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
        snapshotStub.setExpectedBearerToken("rb-r2-token");

        consumerProps = new SearchConsumerProperties();
        consumerProps.setEnabled(true);
        consumerProps.getStream().setEnabled(true);
        consumerProps.getStream().setGroup(GROUP);
        consumerProps.getStream().setBatchSize(20);
        consumerProps.getStream().setBlockTimeoutMs(50L);
        consumerProps.getStream().setMinIdleMs(10L);
        consumerProps.getStream().getRedis().setHost("127.0.0.1");
        consumerProps.getStream().getRedis().setPort(streamRedis.port());
        consumerProps.getStream().getRedis().setConnectTimeoutMs(400);
        consumerProps.getStream().getRedis().setCommandTimeoutMs(800);

        consumerProps.getIndexer().setEnabled(true);
        consumerProps.getIndexer().setBatchSize(20);
        consumerProps.getIndexer().setLeaseSeconds(10);
        consumerProps.getIndexer().setMaxAttempts(3);
        consumerProps.getIndexer().setBaseRetryDelayMs(500L);
        consumerProps.getIndexer().setMaxRetryDelayMs(5000L);
        consumerProps.getIndexer().setRefreshPolicy("wait_for");
        consumerProps.getIndexer().setCacheCompensationBatchSize(10);
        consumerProps.getIndexer().setCacheCompensationBaseDelayMs(500L);
        consumerProps.getIndexer().setCacheCompensationMaxDelayMs(5000L);

        rebuildProps = new SearchRebuildProperties();
        rebuildProps.setEnabled(true);
        rebuildProps.setInstanceId("rb-r2-inst-1");
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
                return "rb-r2-token";
            }

            @Override
            public void invalidate(String audience, List<String> permissions) {
            }
        };

        consumerRepository = new SearchConsumerRepository(jdbcTemplate, MAPPER);
        rebuildRepository = new SearchRebuildRepository(jdbcTemplate, consumerRepository, MAPPER);
        eventParser = new ContentEventParser();
        ingestionService = new ContentEventIngestionService(
                eventParser, consumerRepository, txTemplate, consumerProps, ALIAS, visibilityClient, MAPPER, clock);
        streamClient = new LettuceContentStreamClient(
                STREAM_KEY, GROUP, "knode-search-r2-c1", consumerProps.getStream().getRedis());
        streamConsumer = new ContentStreamConsumer(streamClient, ingestionService, consumerProps);

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
        rebuildService = createRebuildService("rb-r2-inst-1");

        txTemplate.executeWithoutResult(status ->
                consumerRepository.ensureAndLockGeneration(ALIAS, G1, clock.millis()));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (streamClient != null) {
            streamClient.close();
        }
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
        if (streamRedis != null) {
            streamRedis.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // =========================================================================
    // Fix 1: Late-arriving empty-alias repair CANNOT re-attach g2 after g3 cutover
    // =========================================================================
    @Test
    void preventsLateEmptyAliasRepairFromReattachingOldCandidateAfterSubsequentCutover() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(901L, 1L, "Gen2 Article Alpha", "Sum G2", "Body G2",
                        1L, "Tech", List.of("ES"), 10L, 1L, "2026-10-01T00:00:00Z")
        ));
        visibilityTable.put("901", new VisibilityDecision("901", "1", "PUBLIC", "READABLE"));

        RebuildJobRecord jobG2 = driveToSwitchingAlias(rebuildService, "req-r2-empty-repair-g2");
        assertThat(esStub.aliasTargets(SearchIndexAdminClient.attachFenceAlias(G2))).containsExactly(G2);

        // Step 1: First POST /_aliases (g1 -> g2) suffers partial per-action failure that ONLY removes g1,
        //         leaving ALIAS empty ({})!
        // Step 2: Second POST /_aliases (empty-alias repair for g2) is captured and timed out in flight!
        AtomicInteger aliasPostSeq = new AtomicInteger(0);
        AtomicReference<String> capturedEmptyRepairPayload = new AtomicReference<>();
        esStub.setInterceptor((req, def) -> {
            if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())) {
                int seq = aliasPostSeq.incrementAndGet();
                if (seq == 1) {
                    // Partial failure: remove(g1) executed, add(g2) failed -> empty alias!
                    esStub.setAlias(ALIAS);
                    return StatefulElasticsearchStub.StubResponse.ok(
                            "{\"acknowledged\":false,\"errors\":true,\"items\":["
                                    + "{\"remove\":{\"index\":\"" + G1 + "\",\"status\":200,\"acknowledged\":true}},"
                                    + "{\"add\":{\"index\":\"" + G2 + "\",\"status\":500,\"error\":\"add_failed\"}}]}");
                }
                if (seq == 2) {
                    // Empty-alias repair request times out BEFORE reaching ES!
                    capturedEmptyRepairPayload.set(req.body());
                    return StatefulElasticsearchStub.StubResponse.delayedStatus(200, "{\"acknowledged\":true}", 1200L);
                }
            }
            return def.get();
        });

        // Tick 1: partial failure leaves ALIAS empty -> MUST keep SWITCHING_ALIAS gate!
        SearchRebuildService.StepOutcome partialOutcome = rebuildService.advanceJob(jobG2.jobId());
        assertThat(partialOutcome.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(partialOutcome.job().phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(esStub.aliasTargets(ALIAS)).isEmpty();
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);

        // Tick 2: empty-alias repair fires and times out -> MUST keep SWITCHING_ALIAS gate and carry must_exist fence!
        SearchRebuildService.StepOutcome repairTimedOut = rebuildService.advanceJob(jobG2.jobId());
        assertThat(repairTimedOut.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(repairTimedOut.job().phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(esStub.aliasTargets(ALIAS)).isEmpty();
        assertThat(capturedEmptyRepairPayload.get()).isNotNull();

        JsonNode repairActions = MAPPER.readTree(capturedEmptyRepairPayload.get()).path("actions");
        assertThat(repairActions.size()).isEqualTo(2);
        assertThat(repairActions.get(0).path("remove").path("index").asText()).isEqualTo(G2);
        assertThat(repairActions.get(0).path("remove").path("alias").asText())
                .isEqualTo(SearchIndexAdminClient.attachFenceAlias(G2));
        assertThat(repairActions.get(0).path("remove").path("must_exist").asBoolean()).isTrue();
        assertThat(repairActions.get(1).path("add").path("index").asText()).isEqualTo(G2);
        assertThat(repairActions.get(1).path("add").path("alias").asText()).isEqualTo(ALIAS);

        // Tick 3: Simulate cross-instance / restart recovery after lease expiry -> recovers g2 cleanly!
        esStub.clearInterceptor();
        clock.advance(Duration.ofSeconds(15));
        SearchRebuildService restartedInst2 = createRebuildService("rb-r2-inst-2");
        SearchRebuildService.StepOutcome recoveredG2 = restartedInst2.advanceJob(jobG2.jobId());
        assertThat(recoveredG2.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        assertThat(recoveredG2.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(esStub.aliasTargets(SearchIndexAdminClient.attachFenceAlias(G2))).isEmpty();
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(2L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.NORMAL);

        // Step 4: Run subsequent cutover to g3!
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(902L, 1L, "Gen3 Authoritative Article", "Sum G3", "Body G3",
                        1L, "Tech", List.of("ES"), 30L, 1L, "2026-10-02T00:00:00Z")
        ));
        visibilityTable.put("902", new VisibilityDecision("902", "1", "PUBLIC", "READABLE"));
        RebuildJobRecord jobG3 = restartedInst2.startRebuild("req-r2-cutover-g3");
        RebuildJobRecord finishedG3 = restartedInst2.runUntilTerminalOrLimit(jobG3.jobId(), 20);
        assertThat(finishedG3.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(finishedG3.candidatePhysicalIndex()).isEqualTo(G3);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);

        // Step 5: Delayed timed-out empty-alias repair for g2 FINALLY arrives at ES after g3 is active!
        //         MUST be atomically rejected with HTTP 404 (attach fence already consumed) and NEVER re-attach g2!
        StatefulElasticsearchStub.StubResponse lateRepairResult =
                esStub.applyUpdateAliasesBodyDirectly(capturedEmptyRepairPayload.get());
        assertThat(lateRepairResult.status()).isEqualTo(404);
        assertThat(lateRepairResult.body()).contains("aliases_not_found_exception");

        // Final invariants: alias points SOLELY to g3, local active generation is 3, gate is NORMAL, search hits g3!
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);
        IndexGenerationState finalGen = consumerRepository.findGenerationState(ALIAS).orElseThrow();
        assertThat(finalGen.activeGeneration()).isEqualTo(3L);
        assertThat(finalGen.activePhysicalIndex()).isEqualTo(G3);
        assertThat(finalGen.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);

        PublicArticleSearchService searchService = createPublicSearchService();
        PageResult g3Hit = searchService.search("Gen3", 1, 5, "relevance", "q-r2-g3");
        assertThat(g3Hit.rows()).hasSize(1);
        assertThat(g3Hit.rows().get(0).id()).isEqualTo(902L);
        assertThat(searchService.search("Gen2", 1, 5, "relevance", "q-r2-g2").rows()).isEmpty();
    }

    // =========================================================================
    // Fix 1B: Empty-alias bootstrap (fresh empty alias) is also fenced against late arrival
    // =========================================================================
    @Test
    void fencesEmptyAliasBootstrapAgainstLateReattachAfterSubsequentCutover() throws Exception {
        // Start with an empty alias (fresh bootstrap scenario)
        esStub.setAlias(ALIAS);
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(911L, 1L, "Bootstrap Gen2 Doc", "Sum", "Body",
                        1L, "Tech", List.of(), 5L, 1L, "2026-10-01T00:00:00Z")
        ));
        RebuildJobRecord jobG2 = driveToSwitchingAlias(rebuildService, "req-r2-bootstrap-g2");

        AtomicReference<String> capturedBootstrapPayload = new AtomicReference<>();
        AtomicInteger posts = new AtomicInteger(0);
        esStub.setInterceptor((req, def) -> {
            if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())
                    && posts.incrementAndGet() == 1) {
                capturedBootstrapPayload.set(req.body());
                return StatefulElasticsearchStub.StubResponse.delayedStatus(200, "{\"acknowledged\":true}", 1200L);
            }
            return def.get();
        });

        SearchRebuildService.StepOutcome timedOut = rebuildService.advanceJob(jobG2.jobId());
        assertThat(timedOut.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(esStub.aliasTargets(ALIAS)).isEmpty();
        assertThat(capturedBootstrapPayload.get()).isNotNull();

        esStub.clearInterceptor();
        SearchRebuildService.StepOutcome recovered = rebuildService.advanceJob(jobG2.jobId());
        assertThat(recovered.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);

        // Next cutover to g3
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(912L, 1L, "Bootstrap Gen3 Doc", "Sum", "Body",
                        1L, "Tech", List.of(), 9L, 1L, "2026-10-02T00:00:00Z")
        ));
        RebuildJobRecord jobG3 = rebuildService.startRebuild("req-r2-bootstrap-g3");
        assertThat(rebuildService.runUntilTerminalOrLimit(jobG3.jobId(), 20).phase())
                .isEqualTo(RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);

        // Replay timed-out bootstrap payload -> rejected with 404, alias stays solely g3!
        StatefulElasticsearchStub.StubResponse lateBootstrap =
                esStub.applyUpdateAliasesBodyDirectly(capturedBootstrapPayload.get());
        assertThat(lateBootstrap.status()).isEqualTo(404);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);
    }

    // =========================================================================
    // Fix 2: Stale g2 cache compensation CANNOT delete g3 cutover bypass
    // =========================================================================
    @Test
    void preventsStaleG2CacheCompensationFromDeletingG3CutoverBypassAndPreservesG3Isolation() throws Exception {
        // 1) Cut over g1 -> g2 with NO article matching "TargetInG3Only", simulating Redis INCR failure on g2
        //    so g2 enters CACHE_INVALIDATING with active_cache_invalidation_status = PENDING.
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(921L, 1L, "Baseline Gen2 Only", "Sum", "Body",
                        1L, "Cat", List.of(), 10L, 1L, "2026-10-01T00:00:00Z")
        ));
        visibilityTable.put("921", new VisibilityDecision("921", "1", "PUBLIC", "READABLE"));

        cacheRedis.failCommand("INCR", "ERR redis incr down during g2 cutover");
        RebuildJobRecord jobG2 = rebuildService.startRebuild("req-r2-cache-g2");
        RebuildJobRecord pendingG2 = advanceUntilPhase(rebuildService, jobG2.jobId(), RebuildPhase.CACHE_INVALIDATING);
        assertThat(pendingG2.phase()).isEqualTo(RebuildPhase.CACHE_INVALIDATING);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey()))
                .isEqualTo(SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 2L));
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeCacheInvalidationStatus())
                .isEqualTo(CacheInvalidationStatus.PENDING);

        // 2) Restore Redis INCR and advance clock so g2 compensation is due.
        //    Start two stale g2 compensation workers:
        //    - workerA_afterIncr: selects g2, executes INCR, then PAUSES right before clearing cutover_bypass!
        //    - workerA_beforeIncr: selects g2, then PAUSES right before INCR!
        cacheRedis.clearFaults();
        clock.advance(Duration.ofSeconds(2));

        CountDownLatch aAfterIncrPaused = new CountDownLatch(1);
        CountDownLatch releaseAAfterIncr = new CountDownLatch(1);
        SearchIndexTaskWorker workerAAfterIncr = new SearchIndexTaskWorker(
                consumerRepository, txTemplate, indexWriter, cacheInvalidator, consumerProps, ALIAS, clock) {
            @Override
            protected void afterCacheEpochAdvancedBeforeBypassClear(
                    List<SearchConsumerRepository.GenerationCacheCompensationCandidate> generationCandidates) {
                aAfterIncrPaused.countDown();
                try {
                    releaseAAfterIncr.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        CountDownLatch aBeforeIncrPaused = new CountDownLatch(1);
        CountDownLatch releaseABeforeIncr = new CountDownLatch(1);
        SearchIndexTaskWorker workerABeforeIncr = new SearchIndexTaskWorker(
                consumerRepository, txTemplate, indexWriter, cacheInvalidator, consumerProps, ALIAS, clock) {
            @Override
            protected void beforeExecutingCacheCompensation(
                    List<SearchConsumerRepository.CacheCompensationCandidate> taskCandidates,
                    List<SearchConsumerRepository.GenerationCacheCompensationCandidate> generationCandidates) {
                aBeforeIncrPaused.countDown();
                try {
                    releaseABeforeIncr.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SearchIndexTaskWorker.CacheCompensationSummary> futureAAfterIncr =
                    pool.submit(workerAAfterIncr::processPendingCacheInvalidations);
            Future<SearchIndexTaskWorker.CacheCompensationSummary> futureABeforeIncr =
                    pool.submit(workerABeforeIncr::processPendingCacheInvalidations);
            assertThat(aAfterIncrPaused.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(aBeforeIncrPaused.await(5, TimeUnit.SECONDS)).isTrue();

            // 3) Instance B completes g2 compensation and finishes jobG2!
            SearchIndexTaskWorker.CacheCompensationSummary bComp = indexWorker.processPendingCacheInvalidations();
            assertThat(bComp.completedCount()).isEqualTo(1);
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(rebuildService.advanceJob(jobG2.jobId()).job().phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeCacheInvalidationStatus())
                    .isEqualTo(CacheInvalidationStatus.DONE);
            String g2Epoch = cacheRedis.getRaw(cacheInvalidator.epochKey());
            assertThat(g2Epoch).isNotNull();

            // 4) Warm g2's empty-result cache for query "TargetInG3Only" (2 queries to satisfy admissionThreshold=2).
            PublicArticleSearchService queryNode = createPublicSearchService();
            assertThat(queryNode.search("TargetInG3Only", 1, 5, "relevance", "q-g2-empty-1").rows()).isEmpty();
            assertThat(queryNode.search("TargetInG3Only", 1, 5, "relevance", "q-g2-empty-2").rows()).isEmpty();
            assertThat(cacheRedis.snapshot().keySet())
                    .anyMatch(k -> k.contains(":result:") && k.endsWith(":e" + g2Epoch));

            // 5) Cut over g2 -> g3 (g3 HAS matching article 922 "TargetInG3Only Fresh"),
            //    failing Redis INCR while GET & EVAL remain available!
            snapshotStub.setLivePublicArticles(List.of(
                    LoopbackContentSnapshotStub.snapshotItem(921L, 1L, "Baseline Gen2 Only", "Sum", "Body",
                            1L, "Cat", List.of(), 10L, 1L, "2026-10-01T00:00:00Z"),
                    LoopbackContentSnapshotStub.snapshotItem(922L, 1L, "TargetInG3Only Fresh Article", "Sum", "Body",
                            1L, "Cat", List.of(), 88L, 1L, "2026-10-03T00:00:00Z")
            ));
            visibilityTable.put("922", new VisibilityDecision("922", "1", "PUBLIC", "READABLE"));

            cacheRedis.failCommand("INCR", "ERR redis incr down during g3 cutover");
            RebuildJobRecord jobG3 = rebuildService.startRebuild("req-r2-cache-g3");
            RebuildJobRecord pendingG3 = advanceUntilPhase(rebuildService, jobG3.jobId(), RebuildPhase.CACHE_INVALIDATING);
            assertThat(pendingG3.phase()).isEqualTo(RebuildPhase.CACHE_INVALIDATING);
            assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G3);
            String g3OwnerToken = SearchCacheInvalidator.cutoverBypassOwnerToken(ALIAS, 3L);
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(g3OwnerToken);
            assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeCacheInvalidationStatus())
                    .isEqualTo(CacheInvalidationStatus.PENDING);

            // 6) Release BOTH stale g2 compensation instances!
            //    workerAAfterIncr executes atomic Lua compare-and-delete with owner "alias:g2" against live Redis
            //    (where value is "alias:g3") and tries to mark generation 2 DONE in DB;
            //    workerABeforeIncr hits INCR failure and tries to reschedule generation 2 retry.
            releaseAAfterIncr.countDown();
            releaseABeforeIncr.countDown();
            SearchIndexTaskWorker.CacheCompensationSummary staleRes1 = futureAAfterIncr.get(5, TimeUnit.SECONDS);
            SearchIndexTaskWorker.CacheCompensationSummary staleRes2 = futureABeforeIncr.get(5, TimeUnit.SECONDS);
            assertThat(staleRes1.completedCount()).isEqualTo(0);
            assertThat(staleRes2.failedCount()).isEqualTo(0);

            // Assert g3 bypass STILL exists with g3 owner, g3 compensation STILL PENDING, and query STILL hits g3!
            assertThat(cacheRedis.getRaw(cacheInvalidator.cutoverBypassKey())).isEqualTo(g3OwnerToken);
            IndexGenerationState g3State = consumerRepository.findGenerationState(ALIAS).orElseThrow();
            assertThat(g3State.activeGeneration()).isEqualTo(3L);
            assertThat(g3State.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);

            PageResult liveG3Hit = queryNode.search("TargetInG3Only", 1, 5, "relevance", "q-g3-live-1");
            assertThat(liveG3Hit.rows()).hasSize(1);
            assertThat(liveG3Hit.rows().get(0).id()).isEqualTo(922L);
            assertThat(liveG3Hit.rows().get(0).title()).isEqualTo("TargetInG3Only Fresh Article");

            PublicArticleSearchService restartedQueryNode = createPublicSearchService();
            PageResult restartedG3Hit = restartedQueryNode.search("TargetInG3Only", 1, 5, "relevance", "q-g3-live-2");
            assertThat(restartedG3Hit.rows()).hasSize(1);
            assertThat(restartedG3Hit.rows().get(0).id()).isEqualTo(922L);

            // 7) Restore Redis INCR -> g3 compensation succeeds, clears g3 bypass, completes jobG3, resumes caching!
            cacheRedis.clearFaults();
            clock.advance(Duration.ofSeconds(2));
            SearchIndexTaskWorker.CacheCompensationSummary g3Comp = indexWorker.processPendingCacheInvalidations();
            assertThat(g3Comp.completedCount()).isEqualTo(1);
            assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
            assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeCacheInvalidationStatus())
                    .isEqualTo(CacheInvalidationStatus.DONE);

            RebuildJobRecord completedG3 = rebuildService.advanceJob(jobG3.jobId()).job();
            assertThat(completedG3.phase()).isEqualTo(RebuildPhase.COMPLETED);
            String g3Epoch = cacheRedis.getRaw(cacheInvalidator.epochKey());
            assertThat(Long.parseLong(g3Epoch)).isGreaterThan(Long.parseLong(g2Epoch));

            // Subsequent queries warm and hit new g3Epoch cache!
            restartedQueryNode.search("TargetInG3Only", 1, 5, "relevance", "q-g3-cache-1");
            restartedQueryNode.search("TargetInG3Only", 1, 5, "relevance", "q-g3-cache-2");
            assertThat(cacheRedis.snapshot().keySet())
                    .anyMatch(k -> k.contains(":result:") && k.endsWith(":e" + g3Epoch));
        } finally {
            releaseAAfterIncr.countDown();
            releaseABeforeIncr.countDown();
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // Fix 3: Lagging WITHDRAWN v6 keeps public v7 pending without inbox/barrier/task,
    //        then pending reclaim applies & indexes the SAME record when 03 confirms PUBLIC v7
    // =========================================================================
    @Test
    void keepsPublicV7PendingOnLaggingWithdrawnV6ThenReclaimsAndIndexesSameRecordOnPublicV7() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(800L, 1L, "Cold Baseline 800", "Sum", "Body",
                        1L, "Cat", List.of(), 1L, 1L, "2026-10-01T00:00:00Z")
        ));
        RebuildJobRecord job = rebuildService.startRebuild("req-r2-cold-withdrawn-lag");
        assertThat(rebuildService.runUntilTerminalOrLimit(job.jobId(), 20).phase())
                .isEqualTo(RebuildPhase.COMPLETED);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().requireColdVisibilityGuard()).isTrue();

        // 1) Content stub returns lagging WITHDRAWN v6 for cold article 801 when public v7 arrives on Stream.
        visibilityTable.put("801", new VisibilityDecision("801", "6", "WITHDRAWN", "DENIED"));
        String pub801v7 = publicArticleEventJson("evt-r2-801-v7", "ArticlePublished", 801L, 7L,
                "Authoritative Reclaimed 801 v7", "Sum 801", "Body 801", 19L, 2L);
        String recId801 = streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", pub801v7));

        ContentStreamConsumer.PollSummary firstPoll = streamConsumer.pollOnce();
        assertThat(firstPoll.ackedCount()).isEqualTo(0);
        assertThat(firstPoll.dbFailedCount()).isEqualTo(1);

        // Original Stream record MUST stay pending; ZERO terminal inbox, ZERO barrier advance, ZERO projection/task!
        assertThat(streamRedis.pendingRecordIds(STREAM_KEY, GROUP)).containsExactly(recId801);
        assertThat(consumerRepository.findInboxStatus("evt-r2-801-v7")).isEmpty();
        assertThat(consumerRepository.findVersionBarrier(801L)).isEmpty();
        assertThat(consumerRepository.findProjection(2L, 801L)).isEmpty();
        assertThat(consumerRepository.findIndexTask(2L, 801L)).isEmpty();
        assertThat(esStub.getWrittenDocument(G2, "801")).isNull();

        // Also verify missing sourceRevision on WITHDRAWN/DENIED stays retryable without XACK or barrier!
        visibilityTable.put("802", new VisibilityDecision("802", null, "WITHDRAWN", "DENIED"));
        String pub802v5 = publicArticleEventJson("evt-r2-802-v5", "ArticlePublished", 802L, 5L,
                "Missing Rev Withdrawn 802 v5", "Sum", "Body", 3L, 1L);
        String recId802 = streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", pub802v5));
        ContentStreamConsumer.PollSummary missingRevPoll = streamConsumer.pollOnce();
        assertThat(missingRevPoll.ackedCount()).isEqualTo(0);
        assertThat(missingRevPoll.dbFailedCount()).isEqualTo(1);
        assertThat(streamRedis.pendingRecordIds(STREAM_KEY, GROUP)).containsExactly(recId801, recId802);
        assertThat(consumerRepository.findInboxStatus("evt-r2-802-v5")).isEmpty();
        assertThat(consumerRepository.findVersionBarrier(802L)).isEmpty();
        assertThat(consumerRepository.findIndexTask(2L, 802L)).isEmpty();

        // 2) Content catches up and confirms PUBLIC v7 for 801 -> pending reclaim takes over the SAME record & eventId!
        visibilityTable.put("801", new VisibilityDecision("801", "7", "PUBLIC", "READABLE"));
        streamRedis.agePendingEntry(STREAM_KEY, GROUP, recId801, 50L);

        ContentStreamConsumer.PollSummary reclaimPoll = streamConsumer.pollOnce();
        assertThat(reclaimPoll.reclaimedCount()).isEqualTo(1);
        assertThat(reclaimPoll.ackedCount()).isEqualTo(1);
        assertThat(streamRedis.pendingRecordIds(STREAM_KEY, GROUP)).containsExactly(recId802);
        assertThat(consumerRepository.findInboxStatus("evt-r2-801-v7")).contains("APPLIED");

        ArticleVersionBarrier barrier801 = consumerRepository.findVersionBarrier(801L).orElseThrow();
        assertThat(barrier801.maxContentRevision()).isEqualTo(7L);
        assertThat(barrier801.lastVisibility()).isEqualTo(ArticleVisibility.PUBLIC);
        assertThat(consumerRepository.findIndexTask(2L, 801L)).isPresent();

        // Index worker writes reclaimed article 801 into active generation g2!
        indexWorker.processOnce();
        StatefulElasticsearchStub.StoredDoc doc801 = esStub.getRefreshedDocument(G2, "801");
        assertThat(doc801).isNotNull();
        assertThat(doc801.sourceJson()).contains("Authoritative Reclaimed 801 v7");
        assertThat(doc801.sourceJson()).contains("\"sourceRevision\":\"7\"");
        assertThat(doc801.sourceJson()).contains("\"searchable\":true");
    }

    private RebuildJobRecord driveToSwitchingAlias(SearchRebuildService svc, String reqId) {
        RebuildJobRecord job = svc.startRebuild(reqId);
        return advanceUntilPhase(svc, job.jobId(), RebuildPhase.SWITCHING_ALIAS);
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
        root.put("occurredAt", "2026-11-26T12:00:00Z");
        root.put("aggregateType", "ARTICLE");
        root.put("visibility", "PUBLIC");
        root.put("publicationFactId", "pub-" + articleId);
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
        tag.put("name", "Spring");
        payload.put("createTime", "2026-10-01T00:00:00Z");
        payload.put("publishedAt", "2026-10-01T00:00:00Z");
        payload.put("viewCount", Long.toString(viewCount));
        payload.put("counterRevision", Long.toString(counterRevision));
        return MAPPER.writeValueAsString(root);
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
