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
import com.knode.search.consumer.event.ParsedContentEvent;
import com.knode.search.consumer.indexer.ElasticsearchArticleIndexWriter;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.ArticleVersionBarrier;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.ContentEventIngestionService;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.stream.ContentStreamConsumer;
import com.knode.search.consumer.stream.LettuceContentStreamClient;
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

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchRebuildAndCutoverIntegrationTest {
    private static final String ENV = "it-rebuild";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String GROUP = "knode-search-v1";
    private static final String ALIAS = "knode_it_rebuild_articles_search";
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
    private ArticleSearchCache articleSearchCache;
    private PublicArticleSearchService publicSearchService;
    private Map<String, VisibilityDecision> visibilityTable;
    private AtomicInteger visibilityHttpCalls;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "search_rb_it_" + UUID.randomUUID().toString().replace("-", "");
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

        clock = new MutableClock(Instant.parse("2026-11-26T09:00:00Z"));
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

        SearchElasticsearchProperties esProps = new SearchElasticsearchProperties();
        esProps.setUrl(esStub.baseUrl());
        esProps.setIndex(ALIAS);
        esProps.setConnectTimeoutMs(500);
        esProps.setConnectionRequestTimeoutMs(500);
        esProps.setResponseTimeoutMs(3000);

        SearchQueryProperties queryProps = new SearchQueryProperties();
        queryProps.setTotalTimeoutMs(3000);

        SearchRuntimeConfig runtimeConfig = new SearchRuntimeConfig();
        esClient = runtimeConfig.elasticsearchClient(esProps, queryProps);

        snapshotStub = new LoopbackContentSnapshotStub();
        snapshotStub.setExpectedBearerToken("rb-service-token");

        consumerProps = new SearchConsumerProperties();
        consumerProps.setEnabled(true);
        consumerProps.getStream().setEnabled(true);
        consumerProps.getStream().setGroup(GROUP);
        consumerProps.getStream().setBatchSize(20);
        consumerProps.getStream().setBlockTimeoutMs(50L);
        consumerProps.getStream().setMinIdleMs(100L);
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
        rebuildProps.setInstanceId("rb-inst-1");
        rebuildProps.setSnapshotPageSize(2);
        rebuildProps.setChangeBatchLimit(10);
        rebuildProps.setRequestTimeoutMs(5000L);
        rebuildProps.setJobLeaseSeconds(10);
        rebuildProps.setVerificationSampleSize(20);
        rebuildProps.setMaxSwitchAttempts(3);

        SearchCacheProperties cacheProps = new SearchCacheProperties();
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
        ContentVisibilityClient visibilityClient = (ids, budget, requestId) -> {
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
                return "rb-service-token";
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
                STREAM_KEY, GROUP, "knode-search-c1", consumerProps.getStream().getRedis());
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
        indexAdminClient = new ElasticsearchIndexAdminClient(esClient);
        rebuildService = createRebuildService("rb-inst-1");

        // Initialize generation 1 pointing explicitly to G1 (matching ES alias).
        txTemplate.executeWithoutResult(status ->
                consumerRepository.ensureAndLockGeneration(ALIAS, G1, clock.millis()));

        SearchMetrics metrics = new SearchMetrics(new SimpleMeterRegistry());
        articleSearchCache = new ArticleSearchCache(cacheProvider, MAPPER, cacheProps, metrics, ENV);
        SearchQueryPlanner planner = new SearchQueryPlanner(queryProps, cacheProps, esProps, ENV);
        ElasticsearchArticleSearchRepository searchRepo = new ElasticsearchArticleSearchRepository(esClient, esProps);
        publicSearchService = new PublicArticleSearchService(
                planner, articleSearchCache, searchRepo, visibilityClient, queryProps, metrics);
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

    @Test
    void multiPageSnapshotWithPageFailureRestartConcurrentMutationsAndCutoverToNewIndex() throws Exception {
        // Seed active generation g1 with legacy article 100 v1.
        String legacyEvent = publicArticleEventJson("evt-g1-100-v1", "ArticlePublished", 100L, 1L,
                "Legacy Title 100", "Old Sum", "Old Body", 10L, 1L);
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", legacyEvent));
        streamConsumer.pollOnce();
        indexWorker.processOnce();
        assertThat(esStub.getRefreshedDocument(G1, "100")).isNotNull();

        // Prepare 3-item snapshot (pageSize=2 => 2 pages).
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(100L, 2L, "Snap Title 100", "Snap Sum 100",
                        "Snap Body 100", 1L, "Java", List.of("Spring"), 20L, 2L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(101L, 1L, "Snap Title 101", "Snap Sum 101",
                        "Snap Body 101", 1L, "Java", List.of("Cloud"), 15L, 1L, "2026-10-02T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(102L, 1L, "Snap Title 102", "Snap Sum 102",
                        "Snap Body 102", 2L, "Search", List.of("ES"), 30L, 1L, "2026-10-03T00:00:00Z")
        ));

        RebuildJobRecord job = rebuildService.startRebuild("req-full-1");
        assertThat(rebuildService.advanceJob(job.jobId()).status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED); // INIT -> SNAPSHOT_IMPORTING

        // Page 1 succeeds (articles 100, 101).
        SearchRebuildService.StepOutcome p1 = rebuildService.advanceJob(job.jobId());
        assertThat(p1.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
        assertThat(p1.job().snapshotPagesImported()).isEqualTo(1);
        assertThat(p1.job().snapshotItemsImported()).isEqualTo(2L);
        String savedPageCursor = p1.job().snapshotPageCursor();
        assertThat(savedPageCursor).isNotBlank();

        // Inject transient 503 on Page 2 -> cursor must remain unchanged, old alias still serving g1.
        AtomicBoolean failOnce = new AtomicBoolean(true);
        snapshotStub.setInterceptor((req, def) -> {
            if (req.path().contains("/article-snapshots/") && failOnce.compareAndSet(true, false)) {
                return LoopbackContentSnapshotStub.HttpStubResponse.retryable(503, "{\"error\":{\"code\":\"BUSY\"}}", 1L);
            }
            return def.get();
        });
        SearchRebuildService.StepOutcome p2Fail = rebuildService.advanceJob(job.jobId());
        assertThat(p2Fail.status()).isEqualTo(SearchRebuildService.StepStatus.RETRYABLE_ERROR);
        assertThat(p2Fail.job().snapshotPageCursor()).isEqualTo(savedPageCursor);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);

        // Simulate restart: lease expires and second instance resumes from savedPageCursor!
        clock.advance(Duration.ofSeconds(12));
        SearchRebuildService restartedInstance = createRebuildService("rb-inst-2");
        SearchRebuildService.StepOutcome p2Ok = restartedInstance.advanceJob(job.jobId());
        assertThat(p2Ok.status()).isEqualTo(SearchRebuildService.StepStatus.PROGRESSED);
        assertThat(p2Ok.job().phase()).isEqualTo(RebuildPhase.INCREMENTAL_CATCHUP);
        assertThat(p2Ok.job().snapshotDone()).isTrue();
        assertThat(p2Ok.job().snapshotPageCursor()).isNull();
        assertThat(p2Ok.job().snapshotItemsImported()).isEqualTo(3L);

        // Concurrent mutations happen during rebuild:
        // 1) Article 100 updated to v3 + counter bumped to rev 5 (viewCount=99)
        // 2) Article 101 deleted at v2
        // 3) Article 103 published at v1
        String evt100v3 = publicArticleEventJson("evt-chg-100-v3", "ArticleContentChanged", 100L, 3L,
                "Rebuild Title 100 v3", "Sum v3", "Body v3", 20L, 2L);
        String evt100cnt5 = counterEventJson("evt-chg-100-c5", 100L, 5L, 99L);
        String evt101del = hiddenEventJson("evt-chg-101-del", "ArticleDeleted", 101L, 2L);
        String evt103pub = publicArticleEventJson("evt-chg-103-v1", "ArticlePublished", 103L, 1L,
                "Rebuild Title 103 New", "Sum 103", "Body 103", 5L, 1L);

        // Also deliver evt100v3 & evt101del to live g1 Stream while rebuild is running!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", evt100v3));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", evt101del));
        streamConsumer.pollOnce();
        indexWorker.processOnce();
        assertThat(esStub.getRefreshedDocument(G1, "100").sourceJson()).contains("Rebuild Title 100 v3");

        // Append same authoritative commits to Content article-changes feed.
        snapshotStub.appendCommitBatch(List.of(MAPPER.readTree(evt100v3), MAPPER.readTree(evt100cnt5)));
        snapshotStub.appendCommitBatch(List.of(MAPPER.readTree(evt101del), MAPPER.readTree(evt103pub)));

        // INCREMENTAL_CATCHUP hits done=true -> CANDIDATE_INDEXING -> CUTOVER_PAUSING -> FINAL_CATCHUP
        assertThat(restartedInstance.advanceJob(job.jobId()).job().phase()).isEqualTo(RebuildPhase.CANDIDATE_INDEXING);
        assertThat(restartedInstance.advanceJob(job.jobId()).job().phase()).isEqualTo(RebuildPhase.CUTOVER_PAUSING);
        assertThat(restartedInstance.advanceJob(job.jobId()).job().phase()).isEqualTo(RebuildPhase.FINAL_CATCHUP);

        // While CUTOVER_PAUSED: a new commit arrives (100 v4 + viewCount 120) BOTH in Content changes AND on Redis Stream!
        String evt100v4 = publicArticleEventJson("evt-final-100-v4", "ArticleContentChanged", 100L, 4L,
                "Rebuild Final Cutover Title 100", "Final Sum", "Final Body", 99L, 5L);
        String evt100cnt6 = counterEventJson("evt-final-100-c6", 100L, 6L, 120L);
        snapshotStub.appendCommitBatch(List.of(MAPPER.readTree(evt100v4), MAPPER.readTree(evt100cnt6)));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", evt100v4));

        // Online Stream consumer MUST pause cleanly and NOT XACK the message!
        ContentStreamConsumer.PollSummary pausedPoll = streamConsumer.pollOnce();
        assertThat(pausedPoll.freshCount()).isEqualTo(0);
        assertThat(pausedPoll.ackedCount()).isEqualTo(0);
        assertThat(indexWorker.claimBatch()).isEmpty();
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState()).isEqualTo(IndexGenerationState.CutoverState.CUTOVER_PAUSED);

        // Finish FINAL_CATCHUP -> VERIFYING_CANDIDATE -> SWITCHING_ALIAS -> COMPLETED
        RebuildJobRecord finished = restartedInstance.runUntilTerminalOrLimit(job.jobId(), 10);
        assertThat(finished.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(esStub.indexExists(G1)).isTrue(); // old physical index retained!

        IndexGenerationState activeGen = consumerRepository.findGenerationState(ALIAS).orElseThrow();
        assertThat(activeGen.activeGeneration()).isEqualTo(2L);
        assertThat(activeGen.activePhysicalIndex()).isEqualTo(G2);
        assertThat(activeGen.cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);
        assertThat(activeGen.requireColdVisibilityGuard()).isTrue();

        // Post-cutover: new commit 104 arrives on Redis Stream -> resumed consumer processes unacked evt100v4 (stale-ignored idempotently) AND applies 104 into g2!
        String evt104pub = publicArticleEventJson("evt-post-104-v1", "ArticlePublished", 104L, 1L,
                "Rebuild PostCutover Title 104", "Post Sum", "Post Body", 7L, 1L);
        visibilityTable.put("100", new VisibilityDecision("100", "4", "PUBLIC", "READABLE"));
        visibilityTable.put("102", new VisibilityDecision("102", "1", "PUBLIC", "READABLE"));
        visibilityTable.put("103", new VisibilityDecision("103", "1", "PUBLIC", "READABLE"));
        visibilityTable.put("104", new VisibilityDecision("104", "1", "PUBLIC", "READABLE"));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", evt104pub));

        ContentStreamConsumer.PollSummary resumedPoll = streamConsumer.pollOnce();
        assertThat(resumedPoll.ackedCount()).isEqualTo(2);
        indexWorker.processOnce();

        // Verify g2 docs & tombstone redaction.
        StatefulElasticsearchStub.StoredDoc g2Doc100 = esStub.getRefreshedDocument(G2, "100");
        assertThat(g2Doc100).isNotNull();
        JsonNode src100 = MAPPER.readTree(g2Doc100.sourceJson());
        assertThat(src100.path("title").asText()).isEqualTo("Rebuild Final Cutover Title 100");
        assertThat(src100.path("viewCount").asLong()).isEqualTo(120L);
        assertThat(src100.path("sourceRevision").asText()).isEqualTo("4");

        StatefulElasticsearchStub.StoredDoc g2Tomb101 = esStub.getRefreshedDocument(G2, "101");
        assertThat(g2Tomb101).isNotNull();
        JsonNode tomb101 = MAPPER.readTree(g2Tomb101.sourceJson());
        assertThat(tomb101.path("searchable").asBoolean()).isFalse();
        assertThat(tomb101.has("title")).isFalse();
        assertThat(tomb101.has("content")).isFalse();

        // Verify public search hits g2 via alias and sees 100, 103, 104 (and NEVER deleted 101).
        PageResult result = publicSearchService.search("Rebuild", 1, 10, "relevance", "req-final-search");
        assertThat(result.rows()).extracting(r -> r.id()).containsExactlyInAnyOrder(100L, 103L, 104L);
        // Verify g1 still untouched with its pre-cutover doc 100 v3 (not v4!).
        assertThat(esStub.getRefreshedDocument(G1, "100").sourceJson()).contains("Rebuild Title 100 v3");
    }

    @Test
    void emptySnapshotCompletesPaginationAndCatchesUpSubsequentChanges() throws Exception {
        snapshotStub.setLivePublicArticles(List.of());

        RebuildJobRecord job = rebuildService.startRebuild("req-empty-snap");
        assertThat(rebuildService.advanceJob(job.jobId()).job().phase()).isEqualTo(RebuildPhase.SNAPSHOT_IMPORTING);
        RebuildJobRecord afterEmpty = rebuildService.advanceJob(job.jobId()).job();
        assertThat(afterEmpty.snapshotDone()).isTrue();
        assertThat(afterEmpty.snapshotPageCursor()).isNull();
        assertThat(afterEmpty.snapshotItemsImported()).isEqualTo(0L);
        assertThat(afterEmpty.phase()).isEqualTo(RebuildPhase.INCREMENTAL_CATCHUP);

        // Append a post-snapshot article commit.
        String evt201 = publicArticleEventJson("evt-empty-201", "ArticlePublished", 201L, 1L,
                "EmptySnap Followup Article", "Sum", "Body", 3L, 1L);
        snapshotStub.appendCommitBatch(List.of(MAPPER.readTree(evt201)));

        RebuildJobRecord finished = rebuildService.runUntilTerminalOrLimit(job.jobId(), 12);
        assertThat(finished.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);

        visibilityTable.put("201", new VisibilityDecision("201", "1", "PUBLIC", "READABLE"));
        PageResult res = publicSearchService.search("EmptySnap", 1, 10, "relevance", "req-empty-search");
        assertThat(res.rows()).hasSize(1);
        assertThat(res.rows().get(0).id()).isEqualTo(201L);
    }

    @Test
    void rollsBackEntireMultiEventCommitBatchOnMidTransactionFailureWithoutAdvancingCursor() throws Exception {
        snapshotStub.setLivePublicArticles(List.of());
        AtomicBoolean failOnSecondEvent = new AtomicBoolean(true);
        SearchRebuildService faultService = new SearchRebuildService(
                rebuildRepository, consumerRepository, txTemplate, snapshotClient, eventParser,
                indexWorker, indexAdminClient, cacheInvalidator, consumerProps, rebuildProps,
                ALIAS, "rb-fault-1", MAPPER, clock
        ) {
            @Override
            protected void applyChangeEventInTx(
                    RebuildJobRecord job, long commitSequence, ParsedContentEvent event, long nowMs) {
                if (event.eventId().equals("evt-batch-302") && failOnSecondEvent.compareAndSet(true, false)) {
                    throw new IllegalStateException("simulated_mid_batch_db_failure");
                }
                super.applyChangeEventInTx(job, commitSequence, event, nowMs);
            }
        };

        RebuildJobRecord job = faultService.startRebuild("req-batch-rb");
        faultService.advanceJob(job.jobId()); // INIT -> SNAPSHOT_IMPORTING
        RebuildJobRecord beforeChanges = faultService.advanceJob(job.jobId()).job(); // SNAPSHOT_IMPORTING -> INCREMENTAL_CATCHUP
        String evt301 = publicArticleEventJson("evt-batch-301", "ArticlePublished", 301L, 1L,
                "Batch First", "Sum", "Body", 1L, 1L);
        String evt302 = publicArticleEventJson("evt-batch-302", "ArticlePublished", 302L, 1L,
                "Batch Second", "Sum", "Body", 2L, 1L);
        snapshotStub.appendCommitBatch(List.of(MAPPER.readTree(evt301), MAPPER.readTree(evt302)));
                String initialCursor = beforeChanges.incrementalCursor();

        // Mid-batch failure must roll back BOTH 301 and 302 and preserve cursor & commit sequence 0!
        SearchRebuildService.StepOutcome failed = faultService.advanceJob(job.jobId());
        assertThat(failed.status()).isEqualTo(SearchRebuildService.StepStatus.RETRYABLE_ERROR);
        assertThat(failed.job().incrementalCursor()).isEqualTo(initialCursor);
        assertThat(failed.job().lastCommitSequence()).isEqualTo(0L);
        assertThat(consumerRepository.findProjection(2L, 301L)).isEmpty();
        assertThat(consumerRepository.findProjection(2L, 302L)).isEmpty();
        assertThat(consumerRepository.findIndexTask(2L, 301L)).isEmpty();

        // Retry applies full commit batch atomically and completes rebuild.
        RebuildJobRecord finished = faultService.runUntilTerminalOrLimit(job.jobId(), 12);
        assertThat(finished.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(finished.lastCommitSequence()).isEqualTo(1L);
        assertThat(esStub.getRefreshedDocument(G2, "301")).isNotNull();
        assertThat(esStub.getRefreshedDocument(G2, "302")).isNotNull();
    }

    @Test
    void restartsWithNewClientRequestIdAndCandidateGenerationOnHttp410DuringSnapshotAndIncrementalCatchup() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(311L, 1L, "Snap410 One", "Sum", "Body",
                        1L, "Cat", List.of("T"), 1L, 1L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(312L, 1L, "Snap410 Two", "Sum", "Body",
                        1L, "Cat", List.of("T"), 2L, 1L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(313L, 1L, "Snap410 Three", "Sum", "Body",
                        1L, "Cat", List.of("T"), 3L, 1L, "2026-10-01T00:00:00Z")
        ));

        // Job 1 (g2): create snapshot, import page 1, then expire snapshot -> HTTP 410 -> auto-restart as Job 2 (g3)!
        RebuildJobRecord job1 = rebuildService.startRebuild("req-410-orig");
        rebuildService.advanceJob(job1.jobId()); // INIT -> SNAPSHOT_IMPORTING
        RebuildJobRecord job1AfterP1 = rebuildService.advanceJob(job1.jobId()).job();
        snapshotStub.expireSnapshot(job1AfterP1.snapshotId());

        SearchRebuildService.StepOutcome snap410 = rebuildService.advanceJob(job1.jobId());
        assertThat(snap410.status()).isEqualTo(SearchRebuildService.StepStatus.EXPIRED_410_RESTARTED);
        RebuildJobRecord expiredJob1 = rebuildService.findJob(job1.jobId()).orElseThrow();
        assertThat(expiredJob1.phase()).isEqualTo(RebuildPhase.EXPIRED_SUPERSEDED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);

        RebuildJobRecord job2 = snap410.job();
        assertThat(job2.jobId()).isEqualTo(expiredJob1.supersededByJobId());
        assertThat(job2.clientRequestId()).isNotEqualTo(job1.clientRequestId());
        assertThat(job2.candidateGeneration()).isEqualTo(3L);
        assertThat(job2.candidatePhysicalIndex()).isEqualTo(ALIAS + "_g3");

        // Job 2 (g3): complete snapshot, then expire incremental cursor -> HTTP 410 -> auto-restart as Job 3 (g4)!
        rebuildService.advanceJob(job2.jobId()); // INIT -> SNAPSHOT_IMPORTING
        rebuildService.advanceJob(job2.jobId()); // page 1
        RebuildJobRecord job2ReadyInc = rebuildService.advanceJob(job2.jobId()).job(); // page 2 -> INCREMENTAL_CATCHUP
        assertThat(job2ReadyInc.phase()).isEqualTo(RebuildPhase.INCREMENTAL_CATCHUP);
        snapshotStub.expireChangeCursor(job2ReadyInc.incrementalCursor());

        SearchRebuildService.StepOutcome inc410 = rebuildService.advanceJob(job2.jobId());
        assertThat(inc410.status()).isEqualTo(SearchRebuildService.StepStatus.EXPIRED_410_RESTARTED);
        assertThat(rebuildService.findJob(job2.jobId()).orElseThrow().phase()).isEqualTo(RebuildPhase.EXPIRED_SUPERSEDED);

        RebuildJobRecord job3 = inc410.job();
        assertThat(job3.clientRequestId()).isNotEqualTo(job2.clientRequestId());
        assertThat(job3.candidateGeneration()).isEqualTo(4L);
        assertThat(job3.candidatePhysicalIndex()).isEqualTo(ALIAS + "_g4");

        // Job 3 (g4) completes cleanly and switches alias to g4!
        RebuildJobRecord finished = rebuildService.runUntilTerminalOrLimit(job3.jobId(), 20);
        assertThat(finished.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(ALIAS + "_g4");
        assertThat(esStub.getRefreshedDocument(ALIAS + "_g4", "313")).isNotNull();
    }

    @Test
    void blocksLatePreSnapshotDeletedArticleEventViaAuthoritativeVisibilityAndDurableBarrier() throws Exception {
        // Article 401 was deleted at v8 BEFORE snapshot cut (never existed in 05 DB at all!).
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(400L, 1L, "Live Baseline 400", "Sum", "Body",
                        1L, "Cat", List.of(), 1L, 1L, "2026-10-01T00:00:00Z")
        ));
        RebuildJobRecord job = rebuildService.startRebuild("req-cold-guard");
        RebuildJobRecord finished = rebuildService.runUntilTerminalOrLimit(job.jobId(), 20);
        assertThat(finished.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().requireColdVisibilityGuard()).isTrue();

        // Configure Content authoritative visibility: 401 is DELETED at v8, 402 is genuinely PUBLIC at v1.
        visibilityTable.put("401", new VisibilityDecision("401", "8", "DELETED", "DENIED"));
        visibilityTable.put("402", new VisibilityDecision("402", "1", "PUBLIC", "READABLE"));
        visibilityHttpCalls.set(0);

        // Late pre-snapshot ArticlePublished(401, v7) arrives after cutover -> MUST be blocked!
        String lateDeletedPub = publicArticleEventJson("evt-late-401-v7", "ArticlePublished", 401L, 7L,
                "Ghost Deleted Article 401", "Ghost Sum", "Ghost Body", 1L, 1L);
        ContentEventIngestionService.IngestionOutcome out1 =
                ingestionService.ingestStreamMessage("s-401-1", Map.of("event", lateDeletedPub));
        assertThat(out1).isEqualTo(ContentEventIngestionService.IngestionOutcome.STALE_IGNORED);
        assertThat(visibilityHttpCalls.get()).isEqualTo(1);
        assertThat(consumerRepository.findIndexTask(2L, 401L)).isEmpty();
        assertThat(esStub.getWrittenDocument(G2, "401")).isNull();

        ArticleVersionBarrier barrier = consumerRepository.findVersionBarrier(401L).orElseThrow();
        assertThat(barrier.maxContentRevision()).isEqualTo(8L);
        assertThat(barrier.lastVisibility()).isEqualTo(ArticleVisibility.DELETED);

        // Second even older event (401, v6) is blocked directly by durable barrier with ZERO extra HTTP call!
        String olderDeletedPub = publicArticleEventJson("evt-late-401-v6", "ArticlePublished", 401L, 6L,
                "Older Ghost 401", "Sum", "Body", 1L, 1L);
        ContentEventIngestionService.IngestionOutcome out2 =
                ingestionService.ingestStreamMessage("s-401-2", Map.of("event", olderDeletedPub));
        assertThat(out2).isEqualTo(ContentEventIngestionService.IngestionOutcome.STALE_IGNORED);
        assertThat(visibilityHttpCalls.get()).isEqualTo(1);

        // Genuinely newly published article 402 (v1) passes cold-article visibility guard and enters g2!
        String newPub402 = publicArticleEventJson("evt-new-402-v1", "ArticlePublished", 402L, 1L,
                "Genuine New Article 402", "Sum", "Body", 4L, 1L);
        ContentEventIngestionService.IngestionOutcome out3 =
                ingestionService.ingestStreamMessage("s-402-1", Map.of("event", newPub402));
        assertThat(out3).isEqualTo(ContentEventIngestionService.IngestionOutcome.APPLIED);
        indexWorker.processOnce();
        assertThat(esStub.getRefreshedDocument(G2, "402")).isNotNull();
    }

    @Test
    void revertsConfirmedPreSwitchFailureAndReconcilesLostResponseCrashDuringAliasSwitch() throws Exception {
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(450L, 1L, "Switch Resilience Article", "Sum", "Body",
                        1L, "Cat", List.of(), 10L, 1L, "2026-10-01T00:00:00Z")
        ));

        AtomicBoolean crashAfterAliasSwitch = new AtomicBoolean(false);
        SearchRebuildService crashableService = new SearchRebuildService(
                rebuildRepository, consumerRepository, txTemplate, snapshotClient, eventParser,
                indexWorker, indexAdminClient, cacheInvalidator, consumerProps, rebuildProps,
                ALIAS, "rb-crash-1", MAPPER, clock
        ) {
            @Override
            protected void afterAtomicAliasSwitchBeforeLocalCommit(RebuildJobRecord job) {
                if (crashAfterAliasSwitch.compareAndSet(true, false)) {
                    throw new IllegalStateException("simulated_crash_after_es_aliases_before_sql_commit");
                }
            }
        };

        RebuildJobRecord job = crashableService.startRebuild("req-switch-resilience");
        // Drive right up to SWITCHING_ALIAS.
        RebuildJobRecord current = job;
        for (int i = 0; i < 10 && current.phase() != RebuildPhase.SWITCHING_ALIAS; i++) {
            current = crashableService.advanceJob(job.jobId()).job();
        }
        assertThat(current.phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);

        // Part A: Confirmed pre-switch failure (ES _aliases returns 503 before mutating alias -> GET /_alias still shows g1).
        esStub.setInterceptor((req, def) -> {
            if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())) {
                return StatefulElasticsearchStub.StubResponse.status(503, "{\"error\":\"cluster_unavailable\"}");
            }
            return def.get();
        });
        SearchRebuildService.StepOutcome preSwitchFail = crashableService.advanceJob(job.jobId());
        assertThat(preSwitchFail.status()).isEqualTo(SearchRebuildService.StepStatus.PRE_SWITCH_FAILED_REVERTED);
        assertThat(preSwitchFail.job().phase()).isEqualTo(RebuildPhase.CUTOVER_PAUSING);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);
        esStub.clearInterceptor();

        // Advance back to SWITCHING_ALIAS.
        current = preSwitchFail.job();
        for (int i = 0; i < 6 && current.phase() != RebuildPhase.SWITCHING_ALIAS; i++) {
            current = crashableService.advanceJob(job.jobId()).job();
        }
        assertThat(current.phase()).isEqualTo(RebuildPhase.SWITCHING_ALIAS);

        // Part B: Crash AFTER ES _aliases already switched alias to g2, BEFORE local SQL commit!
        crashAfterAliasSwitch.set(true);
        assertThatThrownBy(() -> crashableService.advanceJob(job.jobId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated_crash_after_es_aliases_before_sql_commit");

        // Verify split state: ES alias is ALREADY on g2, while local SQL is still SWITCHING_ALIAS / active_generation = 1!
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(1L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState()).isEqualTo(IndexGenerationState.CutoverState.SWITCHING_ALIAS);
        long aliasesPostCountBeforeRecovery = esStub.requests().stream()
                .filter(r -> "POST".equalsIgnoreCase(r.method()) && "/_aliases".equals(r.path())).count();

        // Restarted instance takes over after lease expiry -> reconciles forward via GET /_alias without duplicate POST /_aliases!
        clock.advance(Duration.ofSeconds(12));
        SearchRebuildService recoveryInstance = createRebuildService("rb-recovery-2");
        SearchRebuildService.StepOutcome reconciled = recoveryInstance.advanceJob(job.jobId());
        assertThat(reconciled.status()).isEqualTo(SearchRebuildService.StepStatus.ALIAS_RECONCILED_AND_ACTIVATED);
        assertThat(reconciled.job().phase()).isEqualTo(RebuildPhase.COMPLETED);

        long aliasesPostCountAfterRecovery = esStub.requests().stream()
                .filter(r -> "POST".equalsIgnoreCase(r.method()) && "/_aliases".equals(r.path())).count();
        assertThat(aliasesPostCountAfterRecovery).isEqualTo(aliasesPostCountBeforeRecovery);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeGeneration()).isEqualTo(2L);
        assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().cutoverState()).isEqualTo(IndexGenerationState.CutoverState.NORMAL);
    }

    @Test
    void isolatesLateInFlightOldGenerationWorkerFromNewGenerationAndHandlesCacheEpochAndRedisCompensation() throws Exception {
        // 1) Ingest article 501 v1 into g1 and warm search cache under epoch 1.
        String evt501v1 = publicArticleEventJson("evt-iso-501-v1", "ArticlePublished", 501L, 1L,
                "OldG1 Isolation Article", "OldSum", "OldBody", 10L, 1L);
        ingestionService.ingestStreamMessage("s-501-1", Map.of("event", evt501v1));
        indexWorker.processOnce();
        visibilityTable.put("501", new VisibilityDecision("501", "1", "PUBLIC", "READABLE"));

        PageResult warm1 = publicSearchService.search("Isolation", 1, 5, "relevance", "req-w1");
        PageResult warm2 = publicSearchService.search("Isolation", 1, 5, "relevance", "req-w2");
        assertThat(warm1.rows()).hasSize(1);
        assertThat(warm2.rows().get(0).title()).isEqualTo("OldG1 Isolation Article");
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");

        // 2) Queue an update on g1 (501 v2) and CLAIM it so an old worker is IN-FLIGHT right before cutover!
        String evt501v2 = publicArticleEventJson("evt-iso-501-v2", "ArticleContentChanged", 501L, 2L,
                "LateStaleG1 Update Article", "LateSum", "LateBody", 11L, 2L);
        ingestionService.ingestStreamMessage("s-501-2", Map.of("event", evt501v2));
        List<SearchIndexTaskRecord> lateOldBatch = indexWorker.claimBatch();
        assertThat(lateOldBatch).hasSize(1);
        assertThat(lateOldBatch.get(0).indexGeneration()).isEqualTo(1L);
        assertThat(lateOldBatch.get(0).targetIndex()).isEqualTo(G1);

        // 3) Snapshot has article 501 at v3 ("NewG2 Isolation Article"). Fail cache Redis INCR during cutover!
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(501L, 3L, "NewG2 Isolation Article", "NewSum", "NewBody",
                        1L, "Cat", List.of(), 50L, 5L, "2026-10-05T00:00:00Z")
        ));
        cacheRedis.failCommand("INCR", "ERR cache redis down during cutover");

        RebuildJobRecord job = rebuildService.startRebuild("req-late-worker");
        RebuildJobRecord atCachePending = job;
        for (int i = 0; i < 12; i++) {
            SearchRebuildService.StepOutcome step = rebuildService.advanceJob(job.jobId());
            atCachePending = step.job();
            if (step.status() == SearchRebuildService.StepStatus.CACHE_INVALIDATION_PENDING) {
                break;
            }
        }
        assertThat(atCachePending.phase()).isEqualTo(RebuildPhase.CACHE_INVALIDATING);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G2);
        IndexGenerationState pendingGen = consumerRepository.findGenerationState(ALIAS).orElseThrow();
        assertThat(pendingGen.activeGeneration()).isEqualTo(2L);
        assertThat(pendingGen.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);

        // 4) Now the LATE IN-FLIGHT g1 worker finally executes its claimed g1 task AFTER cutover!
        SearchIndexTaskWorker.TaskProcessOutcome lateOutcome = indexWorker.executeClaimedTask(lateOldBatch.get(0));
        assertThat(lateOutcome).isEqualTo(SearchIndexTaskWorker.TaskProcessOutcome.APPLIED_AND_CACHE_INVALIDATED);
        // g1 received the late write, while g2 is COMPLETELY UNTOUCHED at v3 ("NewG2 Isolation Article")!
        assertThat(esStub.getRefreshedDocument(G1, "501").sourceJson()).contains("LateStaleG1 Update Article");
        assertThat(esStub.getRefreshedDocument(G2, "501").sourceJson()).contains("NewG2 Isolation Article");
        assertThat(consumerRepository.findProjection(2L, 501L).orElseThrow().title()).isEqualTo("NewG2 Isolation Article");

        // 5) Start a slow pre-switch-epoch query BEFORE Redis recovery (capturing epoch "1"), pause inside ES,
        //    then recover cache Redis and finish CACHE_INVALIDATING (epoch advances "1" -> "2")!
        cacheRedis.clearData();
        cacheRedis.putRaw(cacheInvalidator.epochKey(), "1");
        cacheRedis.clearFaults();
        // Warm hot admission counter under epoch 1 so slow query WOULD write back if epoch guard didn't block it.
        visibilityTable.put("501", new VisibilityDecision("501", "3", "PUBLIC", "READABLE"));
        publicSearchService.search("Isolation", 1, 5, "relevance", "req-admit-e1");

        CountDownLatch slowAtEs = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicBoolean intercepted = new AtomicBoolean(false);
        esStub.setInterceptor((req, def) -> {
            if ("POST".equalsIgnoreCase(req.method()) && req.path().equals("/" + ALIAS + "/_search")
                    && intercepted.compareAndSet(false, true)) {
                StatefulElasticsearchStub.StubResponse captured = def.get();
                slowAtEs.countDown();
                try {
                    releaseSlow.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return captured;
            }
            return def.get();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PageResult> slowFuture = pool.submit(() ->
                    publicSearchService.search("Isolation", 1, 5, "relevance", "req-slow-pre-cutover"));
            assertThat(slowAtEs.await(5, TimeUnit.SECONDS)).isTrue();

            // Complete CACHE_INVALIDATING -> bumps Redis epoch "1" -> "2" and marks job COMPLETED + generation DONE!
            SearchRebuildService.StepOutcome completedStep = rebuildService.advanceJob(job.jobId());
            assertThat(completedStep.job().phase()).isEqualTo(RebuildPhase.COMPLETED);
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("2");
            assertThat(consumerRepository.findGenerationState(ALIAS).orElseThrow().activeCacheInvalidationStatus())
                    .isEqualTo(CacheInvalidationStatus.DONE);

            // Release slow query -> MUST NOT write stale epoch-1 result into epoch-2 cache!
            releaseSlow.countDown();
            slowFuture.get(5, TimeUnit.SECONDS);
            assertThat(cacheRedis.snapshot().keySet())
                    .noneMatch(k -> k.contains(":result:") && k.endsWith(":e2"));
        } finally {
            esStub.clearInterceptor();
            pool.shutdownNow();
        }

        // 6) Fresh public search hits g2 via alias and returns "NewG2 Isolation Article" cleanly!
        PageResult postCutover = publicSearchService.search("Isolation", 1, 5, "relevance", "req-post-cutover");
        assertThat(postCutover.rows()).hasSize(1);
        assertThat(postCutover.rows().get(0).title()).isEqualTo("NewG2 Isolation Article");
        assertThat(postCutover.rows().get(0).viewCount()).isEqualTo(50L);
    }

    @Test
    void enforcesSingleActiveRebuildJobPerAliasAcrossInstancesAndSupportsLeaseTakeover() {
        snapshotStub.setLivePublicArticles(List.of());
        SearchRebuildService instanceA = createRebuildService("rb-node-A");
        SearchRebuildService instanceB = createRebuildService("rb-node-B");

        RebuildJobRecord jobA = instanceA.startRebuild("req-lock-A");
        assertThatThrownBy(() -> instanceB.startRebuild("req-lock-B"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active rebuild job already exists");
        assertThat(instanceB.findActiveJob()).isPresent();
        assertThat(instanceB.findActiveJob().orElseThrow().jobId()).isEqualTo(jobA.jobId());

        // While instanceA's lease is active, instanceB is fenced off.
        SearchRebuildService.StepOutcome blocked = instanceB.advanceJob(jobA.jobId());
        assertThat(blocked.status()).isEqualTo(SearchRebuildService.StepStatus.LEASE_HELD_BY_OTHER);

        // After lease expiry, instanceB cleanly takes over and finishes the job.
        clock.advance(Duration.ofSeconds(15));
        RebuildJobRecord finished = instanceB.runUntilTerminalOrLimit(jobA.jobId(), 15);
        assertThat(finished.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(finished.leaseOwner()).isEqualTo("rb-node-B");
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
        root.put("occurredAt", "2026-11-26T09:00:00Z");
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

    private static String hiddenEventJson(
            String eventId, String eventType, long articleId, long sourceRevision) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", eventType);
        root.put("schemaVersion", 2);
        root.put("producer", "knode-content");
        root.put("occurredAt", "2026-11-26T09:01:00Z");
        root.put("aggregateType", "ARTICLE");
        root.put("visibility", "DELETED");
        root.putNull("publicationFactId");
        root.put("aggregateId", Long.toString(articleId));
        root.put("sourceRevision", Long.toString(sourceRevision));
        ObjectNode payload = root.putObject("payload");
        payload.put("articleId", Long.toString(articleId));
        return MAPPER.writeValueAsString(root);
    }

    private static String counterEventJson(
            String eventId, long articleId, long counterRevision, long viewCount) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", "ArticleCounterChanged");
        root.put("schemaVersion", 1);
        root.put("producer", "knode-content");
        root.put("occurredAt", "2026-11-26T09:02:00Z");
        root.put("aggregateType", "ARTICLE_COUNTER");
        root.put("visibility", "PUBLIC");
        root.putNull("publicationFactId");
        root.put("aggregateId", Long.toString(articleId));
        root.put("sourceRevision", Long.toString(counterRevision));
        ObjectNode payload = root.putObject("payload");
        payload.put("articleId", Long.toString(articleId));
        payload.put("counterRevision", Long.toString(counterRevision));
        payload.put("viewCount", Long.toString(viewCount));
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
