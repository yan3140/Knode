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
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
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
import com.knode.search.rebuild.es.AliasSwitchResult;
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

class SearchRebuildR1RegressionsTest {
    private static final String ENV = "it-rebuild-r1";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String GROUP = "knode-search-v1";
    private static final String ALIAS = "knode_it_r1_articles_search";
    private static final String G1 = ALIAS + "_g1";
    private static final String G2 = ALIAS + "_g2";
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
    private AtomicInteger visibilityHttpCalls;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "search_rb_r1_" + UUID.randomUUID().toString().replace("-", "");
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

        clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
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
        snapshotStub.setExpectedBearerToken("rb-r1-token");

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
        rebuildProps.setInstanceId("rb-r1-inst-1");
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
        visibilityHttpCalls = new AtomicInteger(0);
        visibilityClient = (ids, budget, requestId) -> {
            visibilityHttpCalls.incrementAndGet();
            Map<String, VisibilityDecision> out = new LinkedHashMap<>();
            for (String id : ids) {
                out.put(id, visibilityTable.getOrDefault(id, new VisibilityDecision(id, null, "MISSING", "DENIED")));
            }
            return out;
        };

        ServiceTokenClient tokenClient = new ServiceTokenClient() {
            @Override
            public String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
                return "rb-r1-token";
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
                STREAM_KEY, GROUP, "knode-search-r1-c1", consumerProps.getStream().getRedis());
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
        rebuildService = createRebuildService("rb-r1-inst-1");

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
    // Fix 1A: Alias switch timeout + late arrival keeps SWITCHING_ALIAS gate
    // =========================================================================
    @Test
    void keepsSwitchingAliasGateOnTimeoutWhenFirstGetStillSeesOldIndexThenReconcilesForwardOnLateArrival() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(601L, 1L, "Timeout Late Arrival Article", "Sum", "Body",
                        1L, "Tech", List.of("ES"), 10L, 1L, "2026-10-01T00:00:00Z")
        ));

        RebuildJobRecord job = driveToSwitchingAlias(rebuildService, "req-r1-timeout");

        // Capture the POST /_aliases payload, hold response past client responseTimeout (700ms) so client
        // hits SocketTimeoutException BEFORE ES mutates alias (first GET /_alias still sees g1!).
        AtomicReference<String> capturedAliasesPayload = new AtomicReference<>();
        AtomicBoolean timedOutOnce = new AtomicBoolean(false);
        esStub.setInterceptor((req, def) -> {
            if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())
                    && timedOutOnce.compareAndSet(false, true)) {
                capturedAliasesPayload.set(req.body());
                // Delay 1200ms (> 700ms client response timeout) without applying yet so first GET sees g1!
                return StatefulElasticsearchStub.StubResponse.delayedStatus(200, "{\"acknowledged\":true}", 1200L);
            }
            return def.get();
        });

        // 1) Switch attempt times out -> immediate GET /_alias still returns g1!
        //    MUST keep SWITCHING_ALIAS gate, alias_switch_uncertain=true, cutover_bypass active, NEVER revert to NORMAL!
        SearchRebuildService.StepOutcome timedOutOutcome = rebuildService.advanceJob(job.jobId());
        assertThat(timedOutOutcome.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(timedOutOutcome.job().phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(timedOutOutcome.job().aliasSwitchUncertain()).isTrue();
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(1L);
        assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isTrue();

        // Verify the captured POST /_aliases request carried strict must_exist=true on remove!
        JsonNode sentActions = MAPPER.readTree(capturedAliasesPayload.get()).path("actions");
        assertThat(sentActions.get(0).path("remove").path("index").asText()).isEqualTo(G1);
        assertThat(sentActions.get(0).path("remove").path("must_exist").asBoolean()).isTrue();
        assertThat(sentActions.get(1).path("add").path("index").asText()).isEqualTo(G2);

        long postCountAfterTimeout = countAliasesPosts();
        assertThat(postCountAfterTimeout).isEqualTo(1L);

        // 2) Next poll while delayed request STILL hasn't landed on ES:
        //    MUST stay SWITCHING_ALIAS, MUST NOT revert to NORMAL, MUST NOT send a conflicting 2nd POST /_aliases!
        esStub.clearInterceptor();
        SearchRebuildService.StepOutcome stillWaiting = rebuildService.advanceJob(job.jobId());
        assertThat(stillWaiting.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(stillWaiting.job().phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);
        assertThat(countAliasesPosts()).isEqualTo(1L);

        // 3) Now the original delayed in-flight POST /_aliases finally lands on ES -> moves alias g1 -> g2!
        StatefulElasticsearchStub.StubResponse lateApply = esStub.applyUpdateAliasesBodyDirectly(capturedAliasesPayload.get());
        assertThat(lateApply.status()).isEqualTo(200);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);

        // 4) Next reconcile observes ES alias == {g2} -> activates g2 forward cleanly with ZERO duplicate POST /_aliases!
        SearchRebuildService.StepOutcome reconciled = rebuildService.advanceJob(job.jobId());
        assertThat(reconciled.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        assertThat(reconciled.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(reconciled.job().aliasSwitchUncertain()).isFalse();
        assertThat(countAliasesPosts()).isEqualTo(1L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(2L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.NORMAL);
        assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
    }

    // =========================================================================
    // Fix 1B: HTTP 200 partial action failure + must_exist stale request guard
    // =========================================================================
    @Test
    void doesNotCompleteOnHttp200PartialAliasFailureReconcilesDualBindingAndRejectsStaleMustExistSwitch() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(602L, 1L, "Partial Alias Failure Article", "Sum", "Body",
                        1L, "Tech", List.of("ES"), 12L, 1L, "2026-10-01T00:00:00Z")
        ));

        RebuildJobRecord job = driveToSwitchingAlias(rebuildService, "req-r1-partial");

        // Inject HTTP 200 carrying acknowledged=false, errors=true, per-action remove failure while add(g2)
        // partially attached g2 alongside g1 -> ES alias ends up in split state {g1, g2}!
        AtomicBoolean injectPartial = new AtomicBoolean(true);
        esStub.setInterceptor((req, def) -> {
            if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())
                    && injectPartial.compareAndSet(true, false)) {
                esStub.setAlias(ALIAS, G1, G2);
                return StatefulElasticsearchStub.StubResponse.ok(
                        "{\"acknowledged\":false,\"errors\":true,\"items\":["
                                + "{\"remove\":{\"index\":\"" + G1 + "\",\"status\":500,\"error\":\"remove_failed\"}},"
                                + "{\"add\":{\"index\":\"" + G2 + "\",\"status\":200,\"acknowledged\":true}}]}");
            }
            return def.get();
        });

        // 1) MUST NOT treat HTTP 200 partial failure as completed! Keeps SWITCHING_ALIAS gate and active=1.
        SearchRebuildService.StepOutcome partialOutcome = rebuildService.advanceJob(job.jobId());
        assertThat(partialOutcome.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_SWITCH_AMBIGUOUS);
        assertThat(partialOutcome.job().phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactlyInAnyOrder(G1, G2);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(1L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState())
                .isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);

        // 2) Next reconcile removes stale G1 (with must_exist=true), confirms solely G2, and activates G2 cleanly!
        esStub.clearInterceptor();
        SearchRebuildService.StepOutcome repaired = rebuildService.advanceJob(job.jobId());
        assertThat(repaired.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        assertThat(repaired.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(2L);

        // 3) Stale/duplicate switch attempt trying to remove G1 (which is no longer bound!) and attach an old
        //    candidate G1_old MUST be atomically rejected via must_exist=true (HTTP 404) leaving alias on G2!
        AliasSwitchResult staleReplay = indexAdminClient.switchAliasAtomically(ALIAS, Set.of(G1), ALIAS + "_g0_stale");
        assertThat(staleReplay.status()).isEqualTo(AliasSwitchResult.Status.CONFIRMED_PRE_SWITCH_REJECTED);
        assertThat(staleReplay.httpStatus()).isEqualTo(404);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
    }

    // =========================================================================
    // Fix 2A: Stale-lease fencing on hanging snapshot page writeback
    // =========================================================================
    @Test
    void fencesStaleInstanceSnapshotPageWritebackAfterLeaseExpiryAndTakeover() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(611L, 1L, "Snap Fence 1", "Sum", "Body",
                        1L, "Cat", List.of(), 10L, 1L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(612L, 1L, "Snap Fence 2", "Sum", "Body",
                        1L, "Cat", List.of(), 20L, 1L, "2026-10-02T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(613L, 1L, "Snap Fence 3", "Sum", "Body",
                        1L, "Cat", List.of(), 30L, 1L, "2026-10-03T00:00:00Z")
        ));

        SearchRebuildService instanceA = createRebuildService("rb-fence-A");
        SearchRebuildService instanceB = createRebuildService("rb-fence-B");

        RebuildJobRecord job = instanceA.startRebuild("req-r1-snap-fence");
        assertThat(instanceA.advanceJob(job.jobId()).job().phase()).isEqualTo(RebuildPhase.SNAPSHOT_IMPORTING);

        CountDownLatch aBlockedOnPage1 = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicBoolean interceptFirstPageRequest = new AtomicBoolean(true);
        snapshotStub.setInterceptor((req, def) -> {
            if ("GET".equalsIgnoreCase(req.method()) && req.path().contains("/article-snapshots/")
                    && interceptFirstPageRequest.compareAndSet(true, false)) {
                LoopbackContentSnapshotStub.HttpStubResponse captured = def.get();
                aBlockedOnPage1.countDown();
                try {
                    releaseA.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return captured;
            }
            return def.get();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SearchRebuildService.StepOutcome> futureA = pool.submit(() -> instanceA.advanceJob(job.jobId()));
            assertThat(aBlockedOnPage1.await(5, TimeUnit.SECONDS)).isTrue();

            // Expire instanceA's lease -> instanceB takes over, imports BOTH snapshot pages, enters INCREMENTAL_CATCHUP!
            clock.advance(Duration.ofSeconds(15));
            SearchRebuildService.StepOutcome bPage1 = instanceB.advanceJob(job.jobId());
            SearchRebuildService.StepOutcome bPage2 = instanceB.advanceJob(job.jobId());
            assertThat(bPage1.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
            assertThat(bPage2.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
            assertThat(bPage2.job().phase()).isEqualTo(RebuildPhase.INCREMENTAL_CATCHUP);
            assertThat(bPage2.job().snapshotDone()).isTrue();
            assertThat(bPage2.job().snapshotPageCursor()).isNull();
            assertThat(bPage2.job().snapshotPagesImported()).isEqualTo(2);
            assertThat(bPage2.job().snapshotItemsImported()).isEqualTo(3L);
            long bProjRev611BeforeRelease = consumerRepository.findProjection(2L, 611L).orElseThrow().projectionRevision();

            // Release hanging instanceA -> MUST be fenced with ZERO overwrite of phase/cursor/counts/projections/lastError!
            releaseA.countDown();
            SearchRebuildService.StepOutcome outcomeA = futureA.get(5, TimeUnit.SECONDS);
            assertThat(outcomeA.status()).isEqualTo(SearchRebuildService.StepStatus.LEASE_LOST_FENCED);

            RebuildJobRecord afterRelease = rebuildRepository.findJob(job.jobId()).orElseThrow();
            assertThat(afterRelease.leaseOwner()).isEqualTo("rb-fence-B");
            assertThat(afterRelease.phase()).isEqualTo(RebuildPhase.INCREMENTAL_CATCHUP);
            assertThat(afterRelease.snapshotDone()).isTrue();
            assertThat(afterRelease.snapshotPageCursor()).isNull();
            assertThat(afterRelease.snapshotPagesImported()).isEqualTo(2);
            assertThat(afterRelease.snapshotItemsImported()).isEqualTo(3L);
            assertThat(afterRelease.lastError()).isNull();
            assertThat(consumerRepository.findProjection(2L, 611L).orElseThrow().projectionRevision())
                    .isEqualTo(bProjRev611BeforeRelease);
        } finally {
            snapshotStub.clearInterceptor();
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // Fix 2B: Stale-lease fencing on hanging incremental catchup writeback
    // =========================================================================
    @Test
    void fencesStaleInstanceIncrementalCatchupWritebackAfterLeaseExpiryAndTakeover() throws Exception {
        snapshotStub.setLivePublicArticles(List.of());
        SearchRebuildService instanceA = createRebuildService("rb-inc-fence-A");
        SearchRebuildService instanceB = createRebuildService("rb-inc-fence-B");

        RebuildJobRecord job = instanceA.startRebuild("req-r1-inc-fence");
        instanceA.advanceJob(job.jobId()); // INIT -> SNAPSHOT_IMPORTING
        assertThat(instanceA.advanceJob(job.jobId()).job().phase()).isEqualTo(RebuildPhase.INCREMENTAL_CATCHUP);

        // Append commit #1 (article 621 v1).
        String evt621v1 = publicArticleEventJson("evt-inc-fence-621-v1", "ArticlePublished", 621L, 1L,
                "Inc Fence v1", "Sum", "Body", 5L, 1L);
        snapshotStub.appendCommitBatch(List.of(MAPPER.readTree(evt621v1)));

        CountDownLatch aBlockedOnChanges = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicBoolean interceptFirstChanges = new AtomicBoolean(true);
        snapshotStub.setInterceptor((req, def) -> {
            if ("GET".equalsIgnoreCase(req.method()) && req.path().contains("/article-changes")
                    && interceptFirstChanges.compareAndSet(true, false)) {
                LoopbackContentSnapshotStub.HttpStubResponse captured = def.get();
                aBlockedOnChanges.countDown();
                try {
                    releaseA.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return captured;
            }
            return def.get();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SearchRebuildService.StepOutcome> futureA = pool.submit(() -> instanceA.advanceJob(job.jobId()));
            assertThat(aBlockedOnChanges.await(5, TimeUnit.SECONDS)).isTrue();

            // Lease expires -> append commit #2 (article 621 v2) -> instanceB takes over and applies both commits!
            clock.advance(Duration.ofSeconds(15));
            String evt621v2 = publicArticleEventJson("evt-inc-fence-621-v2", "ArticleContentChanged", 621L, 2L,
                    "Inc Fence v2 Authoritative", "Sum v2", "Body v2", 8L, 2L);
            snapshotStub.appendCommitBatch(List.of(MAPPER.readTree(evt621v2)));

            SearchRebuildService.StepOutcome bOutcome = instanceB.advanceJob(job.jobId());
            assertThat(bOutcome.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
            assertThat(bOutcome.job().phase()).isEqualTo(RebuildPhase.CANDIDATE_INDEXING);
            String bCursor = bOutcome.job().incrementalCursor();
            assertThat(bOutcome.job().lastCommitSequence()).isEqualTo(2L);
            assertThat(bOutcome.job().incrementalCommitsApplied()).isEqualTo(2L);
            assertThat(rebuildRepository.countRebuildEventLogs(2L)).isEqualTo(2L);
            assertThat(consumerRepository.findProjection(2L, 621L).orElseThrow().title())
                    .isEqualTo("Inc Fence v2 Authoritative");

            // Release hanging instanceA (carrying stale commit #1 and old nextCursor!) -> MUST be fenced & rolled back!
            releaseA.countDown();
            SearchRebuildService.StepOutcome outcomeA = futureA.get(5, TimeUnit.SECONDS);
            assertThat(outcomeA.status()).isEqualTo(SearchRebuildService.StepStatus.LEASE_LOST_FENCED);

            RebuildJobRecord afterRelease = rebuildRepository.findJob(job.jobId()).orElseThrow();
            assertThat(afterRelease.leaseOwner()).isEqualTo("rb-inc-fence-B");
            assertThat(afterRelease.phase()).isEqualTo(RebuildPhase.CANDIDATE_INDEXING);
            assertThat(afterRelease.incrementalCursor()).isEqualTo(bCursor);
            assertThat(afterRelease.lastCommitSequence()).isEqualTo(2L);
            assertThat(afterRelease.incrementalCommitsApplied()).isEqualTo(2L);
            assertThat(afterRelease.lastError()).isNull();
            assertThat(rebuildRepository.countRebuildEventLogs(2L)).isEqualTo(2L);
            assertThat(consumerRepository.findProjection(2L, 621L).orElseThrow().title())
                    .isEqualTo("Inc Fence v2 Authoritative");
        } finally {
            snapshotStub.clearInterceptor();
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // Fix 3: Two-generation cache isolation when Redis INCR fails (GET works!)
    //        including empty-result cache + restarted DB-independent search instance
    // =========================================================================
    @Test
    void isolatesStaleEmptyAndResultCachesAcrossInstancesAndRestartsWhenRedisIncrFailsDuringCutover() throws Exception {
        // 1) Seed g1 with article 631 ("SharedTerm OldG1"), NO article matching "OnlyInG2".
        String evt631v1 = publicArticleEventJson("evt-cache-631-v1", "ArticlePublished", 631L, 1L,
                "SharedTerm OldG1 Title", "OldSum", "OldBody", 10L, 1L);
        ingestionService.ingestStreamMessage("s-631-1", Map.of("event", evt631v1));
        indexWorker.processOnce();
        visibilityTable.put("631", new VisibilityDecision("631", "1", "PUBLIC", "READABLE"));
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");

        // Warm BOTH an empty-result cache ("OnlyInG2") AND a non-empty cache ("SharedTerm") under epoch 1!
        PublicArticleSearchService queryNode1 = createPublicSearchService();
        assertThat(queryNode1.search("OnlyInG2", 1, 5, "relevance", "q-empty-1").rows()).isEmpty();
        assertThat(queryNode1.search("OnlyInG2", 1, 5, "relevance", "q-empty-2").rows()).isEmpty();
        assertThat(queryNode1.search("SharedTerm", 1, 5, "relevance", "q-hit-1").rows().get(0).title())
                .isEqualTo("SharedTerm OldG1 Title");
        assertThat(queryNode1.search("SharedTerm", 1, 5, "relevance", "q-hit-2").rows().get(0).title())
                .isEqualTo("SharedTerm OldG1 Title");

        // Confirm BOTH empty and non-empty results are cached in Redis under :e1!
        long cachedE1Count = cacheRedis.snapshot().keySet().stream()
                .filter(k -> k.contains(":result:") && k.endsWith(":e1"))
                .count();
        assertThat(cachedE1Count).isEqualTo(2L);

        // 2) Snapshot for g2 has:
        //    - 631 v2 ("SharedTerm NewG2 Title")
        //    - 632 v1 ("OnlyInG2 Fresh Article")
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(631L, 2L, "SharedTerm NewG2 Title", "NewSum", "NewBody",
                        1L, "Cat", List.of(), 50L, 2L, "2026-10-05T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(632L, 1L, "OnlyInG2 Fresh Article", "NewSum", "NewBody",
                        1L, "Cat", List.of(), 77L, 1L, "2026-10-06T00:00:00Z")
        ));
        visibilityTable.put("631", new VisibilityDecision("631", "2", "PUBLIC", "READABLE"));
        visibilityTable.put("632", new VisibilityDecision("632", "1", "PUBLIC", "READABLE"));

        // Fail Redis INCR during cutover while GET and SET remain available!
        cacheRedis.failCommand("INCR", "ERR redis incr blocked during cutover");

        RebuildJobRecord job = rebuildService.startRebuild("req-r1-cache-iso");
        RebuildJobRecord pendingJob = job;
        for (int i = 0; i < 12; i++) {
            SearchRebuildService.StepOutcome step = rebuildService.advanceJob(job.jobId());
            pendingJob = step.job();
            if (step.status() == SearchRebuildService.StepStatus.CACHE_INVALIDATION_PENDING) {
                break;
            }
        }
        assertThat(pendingJob.phase()).isEqualTo(RebuildPhase.CACHE_INVALIDATING);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1"); // INCR failed!
        assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isTrue();
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeCacheInvalidationStatus())
                .isEqualTo(CacheInvalidationStatus.PENDING);

        // 3) Query BOTH "OnlyInG2" (previously cached EMPTY under :e1!) and "SharedTerm" (previously cached OldG1
        //    under :e1!) from BOTH original queryNode1 AND a brand-new restarted queryNode2 (zero DB dependency!):
        //    MUST bypass stale :e1 cache and return g2 results immediately!
        PublicArticleSearchService restartedQueryNode2 = createPublicSearchService();

        PageResult emptyFix1 = queryNode1.search("OnlyInG2", 1, 5, "relevance", "q-post-empty-1");
        assertThat(emptyFix1.rows()).hasSize(1);
        assertThat(emptyFix1.rows().get(0).id()).isEqualTo(632L);
        assertThat(emptyFix1.rows().get(0).title()).isEqualTo("OnlyInG2 Fresh Article");

        PageResult emptyFixRestarted = restartedQueryNode2.search("OnlyInG2", 1, 5, "relevance", "q-post-empty-2");
        assertThat(emptyFixRestarted.rows()).hasSize(1);
        assertThat(emptyFixRestarted.rows().get(0).id()).isEqualTo(632L);

        PageResult hitFixRestarted = restartedQueryNode2.search("SharedTerm", 1, 5, "relevance", "q-post-hit-2");
        assertThat(hitFixRestarted.rows()).hasSize(1);
        assertThat(hitFixRestarted.rows().get(0).title()).isEqualTo("SharedTerm NewG2 Title");

        // 4) Recover Redis INCR -> run compensation via SearchIndexTaskWorker + finish rebuild job ->
        //    epoch advances 1 -> 2, cutover_bypass is deleted, normal caching resumes on :e2!
        cacheRedis.clearFaults();
        clock.advance(Duration.ofSeconds(2));
        SearchIndexTaskWorker.CacheCompensationSummary comp = indexWorker.processPendingCacheInvalidations();
        assertThat(comp.completedCount()).isEqualTo(1);
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("2");
        assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeCacheInvalidationStatus())
                .isEqualTo(CacheInvalidationStatus.DONE);

        SearchRebuildService.StepOutcome completedJob = rebuildService.advanceJob(job.jobId());
        assertThat(completedJob.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(cacheRedis.containsKey(cacheInvalidator.cutoverBypassKey())).isFalse();

        // Subsequent queries warm and hit new :e2 cache cleanly!
        restartedQueryNode2.search("OnlyInG2", 1, 5, "relevance", "q-e2-1");
        restartedQueryNode2.search("OnlyInG2", 1, 5, "relevance", "q-e2-2");
        assertThat(cacheRedis.snapshot().keySet())
                .anyMatch(k -> k.contains(":result:") && k.endsWith(":e2"));
    }

    // =========================================================================
    // Fix 4: Cold-article authoritative sourceRevision validation
    // =========================================================================
    @Test
    void validatesAuthoritativeSourceRevisionOnColdArticlesIgnoringStaleAndRetryingLaggingWithoutXack() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(700L, 1L, "Cold Guard Baseline 700", "Sum", "Body",
                        1L, "Cat", List.of(), 1L, 1L, "2026-10-01T00:00:00Z")
        ));
        RebuildJobRecord job = rebuildService.startRebuild("req-r1-cold-rev");
        RebuildJobRecord finished = rebuildService.runUntilTerminalOrLimit(job.jobId(), 20);
        assertThat(finished.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().requireColdVisibilityGuard()).isTrue();

        // Case A: Content authoritative state is PUBLIC v9 for cold article 701.
        //         Late pre-snapshot v7 MUST be ignored without marking v9 applied, then v9 MUST be admitted!
        visibilityTable.put("701", new VisibilityDecision("701", "9", "PUBLIC", "READABLE"));
        String stale701v7 = publicArticleEventJson("evt-cold-701-v7", "ArticlePublished", 701L, 7L,
                "Stale Old Title 701 v7", "OldSum", "OldBody", 5L, 1L);
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", stale701v7));

        ContentStreamConsumer.PollSummary stalePoll = streamConsumer.pollOnce();
        assertThat(stalePoll.ackedCount()).isEqualTo(1);
        assertThat(consumerRepository.findInboxStatus("evt-cold-701-v7")).contains("STALE_IGNORED");
        assertThat(consumerRepository.findIndexTask(2L, 701L)).isEmpty();
        assertThat(esStub.getWrittenDocument(G2, "701")).isNull();
        // Projection must remain without content and barrier must NOT mark v9 as applied!
        ArticleProjectionRecord proj701AfterV7 = consumerRepository.findProjection(2L, 701L).orElseThrow();
        assertThat(proj701AfterV7.hasContent()).isFalse();
        assertThat(proj701AfterV7.contentRevision()).isEqualTo(0L);
        assertThat(consumerRepository.findVersionBarrier(701L).orElseThrow().maxContentRevision()).isEqualTo(0L);

        // Now authoritative v9 arrives -> MUST be admitted and indexed into g2!
        String fresh701v9 = publicArticleEventJson("evt-cold-701-v9", "ArticleContentChanged", 701L, 9L,
                "Authoritative Title 701 v9", "NewSum", "NewBody", 25L, 3L);
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", fresh701v9));
        ContentStreamConsumer.PollSummary freshPoll = streamConsumer.pollOnce();
        assertThat(freshPoll.ackedCount()).isEqualTo(1);
        assertThat(consumerRepository.findInboxStatus("evt-cold-701-v9")).contains("APPLIED");
        indexWorker.processOnce();
        StatefulElasticsearchStub.StoredDoc doc701 = esStub.getRefreshedDocument(G2, "701");
        assertThat(doc701).isNotNull();
        assertThat(doc701.sourceJson()).contains("Authoritative Title 701 v9");
        assertThat(doc701.sourceJson()).contains("\"sourceRevision\":\"9\"");

        // Case B: Content authoritative version LAGS behind event (Content returns PUBLIC v6 when event is v7)
        //         MUST fail retryably WITHOUT XACK and WITHOUT marking inbox!
        visibilityTable.put("702", new VisibilityDecision("702", "6", "PUBLIC", "READABLE"));
        String ahead702v7 = publicArticleEventJson("evt-cold-702-v7", "ArticlePublished", 702L, 7L,
                "Ahead Event 702 v7", "Sum", "Body", 9L, 1L);
        String recId702 = streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", ahead702v7));

        ContentStreamConsumer.PollSummary lagPoll = streamConsumer.pollOnce();
        assertThat(lagPoll.ackedCount()).isEqualTo(0);
        assertThat(lagPoll.dbFailedCount()).isEqualTo(1);
        assertThat(streamRedis.pendingRecordIds(STREAM_KEY, GROUP)).contains(recId702);
        assertThat(consumerRepository.findInboxStatus("evt-cold-702-v7")).isEmpty();
        assertThat(consumerRepository.findIndexTask(2L, 702L)).isEmpty();

        // Case C: Content returns PUBLIC/READABLE with missing sourceRevision -> MUST fail retryably WITHOUT XACK!
        visibilityTable.put("703", new VisibilityDecision("703", null, "PUBLIC", "READABLE"));
        String missingRev703 = publicArticleEventJson("evt-cold-703-v3", "ArticlePublished", 703L, 3L,
                "Missing Rev 703", "Sum", "Body", 1L, 1L);
        String recId703 = streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", missingRev703));

        streamRedis.agePendingEntry(STREAM_KEY, GROUP, recId702, 50L);
        // Now let Content catch up for 702 (PUBLIC v7) while 703 still has missing sourceRevision!
        visibilityTable.put("702", new VisibilityDecision("702", "7", "PUBLIC", "READABLE"));

        ContentStreamConsumer.PollSummary retryPoll = streamConsumer.pollOnce();
        assertThat(retryPoll.reclaimedCount()).isEqualTo(1);
        assertThat(retryPoll.ackedCount()).isEqualTo(1); // 702 succeeded and XACKed!
        assertThat(retryPoll.dbFailedCount()).isEqualTo(1); // 703 failed retryably without XACK!
        assertThat(streamRedis.pendingRecordIds(STREAM_KEY, GROUP)).containsExactly(recId703);
        assertThat(consumerRepository.findInboxStatus("evt-cold-702-v7")).contains("APPLIED");
        assertThat(consumerRepository.findInboxStatus("evt-cold-703-v3")).isEmpty();

        indexWorker.processOnce();
        assertThat(esStub.getRefreshedDocument(G2, "702")).isNotNull();
        assertThat(esStub.getRefreshedDocument(G2, "702").sourceJson()).contains("Ahead Event 702 v7");
        assertThat(esStub.getWrittenDocument(G2, "703")).isNull();
    }

    private RebuildJobRecord driveToSwitchingAlias(SearchRebuildService svc, String reqId) {
        RebuildJobRecord job = svc.startRebuild(reqId);
        RebuildJobRecord current = job;
        for (int i = 0; i < 12 && current.phase() != RebuildPhase.SWITCHING_ALIAS; i++) {
            current = svc.advanceJob(job.jobId()).job();
        }
        assertThat(current.phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);
        return current;
    }

    private long countAliasesPosts() {
        return esStub.requests().stream()
                .filter(r -> "POST".equalsIgnoreCase(r.method()) && "/_aliases".equals(r.path()))
                .count();
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
        // Pure public search stack: ZERO consumer/rebuild DB dependencies!
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
        root.put("occurredAt", "2026-11-26T10:00:00Z");
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
