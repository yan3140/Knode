package com.knode.search.rebuild;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knode.search.auth.AdminPrincipal;
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
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.ArticleVersionBarrier;
import com.knode.search.consumer.persistence.ContentEventIngestionService;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.ContentEventIngestionService.IngestionOutcome;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexOperation;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.consumer.stream.ContentStreamConsumer;
import com.knode.search.consumer.stream.LettuceContentStreamClient;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.PageResult;
import com.knode.search.model.QueryBudget;
import com.knode.search.ops.SearchAdminService;
import com.knode.search.ops.SearchOpsProperties;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRebuildR6RegressionsTest {
    private static final String ENV = "it-rebuild-r6";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String GROUP = "knode-search-v1";
    private static final String ALIAS = "knode_it_r6_articles_search";
    private static final String G1 = ALIAS + "_g1";
    private static final String G2 = ALIAS + "_g2";
    private static final String G4 = ALIAS + "_g4";
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
    private SearchAdminService adminService;
    private ContentVisibilityClient visibilityClient;
    private Map<String, VisibilityDecision> visibilityTable;
    private AtomicInteger visibilityCalls;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "search_rb_r6_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
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

        clock = new MutableClock(Instant.parse("2026-11-27T08:00:00Z"));
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
        snapshotStub.setExpectedBearerToken("rb-r6-token");

        consumerProps = new SearchConsumerProperties();
        consumerProps.setEnabled(true);
        consumerProps.getStream().setEnabled(true);
        consumerProps.getStream().setGroup(GROUP);
        consumerProps.getStream().setBatchSize(20);
        consumerProps.getStream().setBlockTimeoutMs(50L);
        consumerProps.getStream().setMinIdleMs(5000L);
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
        rebuildProps.setInstanceId("rb-r6-inst-1");
        rebuildProps.setSnapshotPageSize(2);
        rebuildProps.setChangeBatchLimit(10);
        rebuildProps.setRequestTimeoutMs(5000L);
        rebuildProps.setJobLeaseSeconds(10);
        rebuildProps.setVerificationSampleSize(20);
        rebuildProps.setMaxSwitchAttempts(2);

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
        visibilityCalls = new AtomicInteger(0);
        visibilityClient = (ids, budget, requestId) -> {
            visibilityCalls.incrementAndGet();
            Map<String, VisibilityDecision> out = new LinkedHashMap<>();
            for (String id : ids) {
                out.put(id, visibilityTable.getOrDefault(id, new VisibilityDecision(id, null, "MISSING", "DENIED")));
            }
            return out;
        };

        ServiceTokenClient tokenClient = new ServiceTokenClient() {
            @Override
            public String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
                return "rb-r6-token";
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
                STREAM_KEY, GROUP, "knode-search-r6-c1", consumerProps.getStream().getRedis());
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
        rebuildService = createRebuildService("rb-r6-inst-1");
        adminService = new SearchAdminService(
                singleObjectProvider(rebuildService),
                null,
                singleObjectProvider(consumerRepository),
                singleObjectProvider(txTemplate),
                singleObjectProvider(indexWorker),
                singleObjectProvider(snapshotClient),
                consumerProps,
                new SearchOpsProperties(),
                esProps,
                new SearchMetrics(new SimpleMeterRegistry()),
                MAPPER,
                clock
        );

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
    // Criterion 1: Candidate snapshot imports PUBLIC v2 before Stream delivers v2;
    // active g1 must still apply, index, serve public search, and stay idempotent.
    // =========================================================================
    @Test
    void candidateSnapshotImportDoesNotSuppressActiveGenerationStreamUpdateAndSearch() throws Exception {
        String v1Json = publicArticleEventJson(
                "evt-1001-v1", "ArticlePublished", 1001L, 1L,
                "Alpha Original V1", "Summary V1", "Legacy body V1", 10L, 1L);
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", v1Json));
        streamConsumer.pollOnce();
        indexWorker.processOnce();
        visibilityTable.put("1001", new VisibilityDecision("1001", "1", "PUBLIC", "READABLE"));

        PublicArticleSearchService publicSearch = createPublicSearchService();
        PageResult initialHit = publicSearch.search("Original", 1, 10, "relevance", "req-r6-init-v1");
        assertThat(initialHit.rows()).hasSize(1);
        assertThat(initialHit.rows().get(0).title()).isEqualTo("Alpha Original V1");

        // Candidate g2 imports PUBLIC v2 in snapshot while alias STILL points to g1.
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(
                        1001L, 2L, "Alpha SnapshotFirst V2", "Summary V2", "Updated body V2",
                        10L, "Tech", List.of("Spring"), 12L, 2L, "2026-10-01T00:00:00Z")
        ));
        RebuildJobRecord job = rebuildService.startRebuild("req-r6-snap-first");
        job = advanceUntilPhase(rebuildService, job.jobId(), RebuildPhase.INCREMENTAL_CATCHUP);
        assertThat(job.candidateGeneration()).isEqualTo(2L);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);

        // Candidate g2 has v2 projection, while global barrier MUST NOT be polluted past g1's v1.
        assertThat(consumerRepository.findProjection(2L, 1001L).orElseThrow().contentRevision()).isEqualTo(2L);
        assertThat(consumerRepository.findVersionBarrier(1001L).orElseThrow().maxContentRevision()).isEqualTo(1L);

        // Stream delivers PUBLIC v2 to active g1.
        String v2Json = publicArticleEventJson(
                "evt-1001-v2", "ArticleContentChanged", 1001L, 2L,
                "Alpha SnapshotFirst V2", "Summary V2", "Updated body V2", 12L, 2L);
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", v2Json));
        streamConsumer.pollOnce();

        // SQL committed before XACK: inbox MUST be APPLIED, pending=0, g1 projection & task at v2.
        assertThat(consumerRepository.findInboxStatus("evt-1001-v2")).contains("APPLIED");
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isZero();
        ArticleProjectionRecord g1Proj = consumerRepository.findProjection(1L, 1001L).orElseThrow();
        assertThat(g1Proj.contentRevision()).isEqualTo(2L);
        assertThat(g1Proj.title()).isEqualTo("Alpha SnapshotFirst V2");
        SearchIndexTaskRecord g1Task = consumerRepository.findIndexTask(1L, 1001L).orElseThrow();
        assertThat(g1Task.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(g1Task.targetProjectionRevision()).isEqualTo(2L);

        // Worker updates g1 ES to v2; public search hits new v2 content when Content confirms v2 visible.
        indexWorker.processOnce();
        assertThat(consumerRepository.findIndexTask(1L, 1001L).orElseThrow().status())
                .isEqualTo(SearchIndexTaskStatus.DONE);
        StatefulElasticsearchStub.StoredDoc g1Doc = esStub.getRefreshedDocument(G1, "1001");
        assertThat(g1Doc).isNotNull();
        assertThat(g1Doc.sourceJson()).contains("Alpha SnapshotFirst V2").contains("\"sourceRevision\":\"2\"");

        visibilityTable.put("1001", new VisibilityDecision("1001", "2", "PUBLIC", "READABLE"));
        PageResult v2Hit = publicSearch.search("SnapshotFirst", 1, 10, "relevance", "req-r6-v2-hit");
        assertThat(v2Hit.rows()).hasSize(1);
        assertThat(v2Hit.rows().get(0).id()).isEqualTo(1001L);
        assertThat(v2Hit.rows().get(0).title()).isEqualTo("Alpha SnapshotFirst V2");

        // Re-delivering the exact same eventId stays idempotent.
        IngestionOutcome dup = ingestionService.ingestStreamMessage("999-0", Map.of("event", v2Json));
        assertThat(dup).isEqualTo(IngestionOutcome.DUPLICATE_SKIPPED);
    }

    // =========================================================================
    // Criterion 2: Candidate incremental catchup reads WITHDRAWN v2, DELETED v2,
    // and PUBLIC v2 before active g1; active g1 must still apply tombstones &
    // public update, wipe body fields, return 0 hits, and block resurrection.
    // =========================================================================
    @Test
    void candidateIncrementalCatchupDoesNotSuppressActiveGenerationWithdrawDeleteOrPublicUpdate() throws Exception {
        for (long id : List.of(2001L, 2002L, 2003L)) {
            String json = publicArticleEventJson(
                    "evt-" + id + "-v1", "ArticlePublished", id, 1L,
                    "ArticleTitle" + id + " V1", "Summary" + id, "SecretBody" + id + " V1", 5L, 1L);
            streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", json));
            visibilityTable.put(Long.toString(id), new VisibilityDecision(Long.toString(id), "1", "PUBLIC", "READABLE"));
        }
        streamConsumer.pollOnce();
        indexWorker.processOnce();

        // Candidate g2 imports v1 snapshot and enters INCREMENTAL_CATCHUP.
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(
                        2001L, 1L, "ArticleTitle2001 V1", "Summary2001", "SecretBody2001 V1",
                        10L, "Tech", List.of("Spring"), 5L, 1L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(
                        2002L, 1L, "ArticleTitle2002 V1", "Summary2002", "SecretBody2002 V1",
                        10L, "Tech", List.of("Spring"), 5L, 1L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(
                        2003L, 1L, "ArticleTitle2003 V1", "Summary2003", "SecretBody2003 V1",
                        10L, "Tech", List.of("Spring"), 5L, 1L, "2026-10-01T00:00:00Z")
        ));
        RebuildJobRecord job = rebuildService.startRebuild("req-r6-inc-first");
        job = advanceUntilPhase(rebuildService, job.jobId(), RebuildPhase.INCREMENTAL_CATCHUP);

        // Append incremental changes: 2001 WITHDRAWN v2, 2002 DELETED v2, 2003 PUBLIC v2.
        String withdrawn2001 = hiddenArticleEventJson("evt-2001-w2", "ArticleVisibilityChanged", 2001L, 2L, "WITHDRAWN");
        String deleted2002 = hiddenArticleEventJson("evt-2002-d2", "ArticleDeleted", 2002L, 2L, "DELETED");
        String public2003 = publicArticleEventJson(
                "evt-2003-u2", "ArticleContentChanged", 2003L, 2L,
                "ArticleTitle2003 IncUpdatedV2", "Summary2003 V2", "PublicBody2003 V2", 8L, 2L);
        snapshotStub.appendCommitBatch(List.of(
                MAPPER.readTree(withdrawn2001),
                MAPPER.readTree(deleted2002),
                MAPPER.readTree(public2003)
        ));

        // Advance candidate g2 ONE incremental step BEFORE active g1 sees Stream events; alias stays on g1.
        job = rebuildService.advanceJob(job.jobId()).job();
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);
        assertThat(consumerRepository.findProjection(2L, 2001L).orElseThrow().visibility())
                .isEqualTo(ArticleVisibility.WITHDRAWN);
        assertThat(consumerRepository.findProjection(2L, 2002L).orElseThrow().visibility())
                .isEqualTo(ArticleVisibility.DELETED);
        assertThat(consumerRepository.findProjection(2L, 2003L).orElseThrow().contentRevision()).isEqualTo(2L);

        // Global barriers MUST remain at v1 until cutover!
        assertThat(consumerRepository.findVersionBarrier(2001L).orElseThrow().maxContentRevision()).isEqualTo(1L);
        assertThat(consumerRepository.findVersionBarrier(2002L).orElseThrow().maxContentRevision()).isEqualTo(1L);
        assertThat(consumerRepository.findVersionBarrier(2003L).orElseThrow().maxContentRevision()).isEqualTo(1L);

        // Now deliver the same 3 v2 events to active g1 via Redis Stream.
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", withdrawn2001));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", deleted2002));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", public2003));
        streamConsumer.pollOnce();

        assertThat(consumerRepository.findInboxStatus("evt-2001-w2")).contains("APPLIED");
        assertThat(consumerRepository.findInboxStatus("evt-2002-d2")).contains("APPLIED");
        assertThat(consumerRepository.findInboxStatus("evt-2003-u2")).contains("APPLIED");
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isZero();

        // Execute g1 ES tasks.
        indexWorker.processOnce();

        // Verify g1 projections & ES tombstones have searchable=false and ZERO leaked body fields.
        for (long hiddenId : List.of(2001L, 2002L)) {
            ArticleProjectionRecord proj = consumerRepository.findProjection(1L, hiddenId).orElseThrow();
            assertThat(proj.contentRevision()).isEqualTo(2L);
            assertThat(proj.title()).isNull();
            assertThat(proj.summary()).isNull();
            assertThat(proj.content()).isNull();

            StatefulElasticsearchStub.StoredDoc doc = esStub.getRefreshedDocument(G1, Long.toString(hiddenId));
            assertThat(doc).isNotNull();
            JsonNode src = MAPPER.readTree(doc.sourceJson());
            assertThat(src.path("searchable").asBoolean()).isFalse();
            assertThat(src.path("sourceRevision").asText()).isEqualTo("2");
            assertThat(src.has("title")).isFalse();
            assertThat(src.has("summary")).isFalse();
            assertThat(src.has("content")).isFalse();
            assertThat(doc.sourceJson()).doesNotContain("SecretBody" + hiddenId);
        }

        // Public search: 2001 & 2002 MUST return 0 hits; 2003 hits updated v2 content.
        PublicArticleSearchService publicSearch = createPublicSearchService();
        assertThat(publicSearch.search("ArticleTitle2001", 1, 10, "relevance", "req-r6-hide-2001").rows()).isEmpty();
        assertThat(publicSearch.search("ArticleTitle2002", 1, 10, "relevance", "req-r6-hide-2002").rows()).isEmpty();
        visibilityTable.put("2003", new VisibilityDecision("2003", "2", "PUBLIC", "READABLE"));
        PageResult hit2003 = publicSearch.search("IncUpdatedV2", 1, 10, "relevance", "req-r6-pub-2003");
        assertThat(hit2003.rows()).hasSize(1);
        assertThat(hit2003.rows().get(0).id()).isEqualTo(2003L);

        // Late stale public upserts cannot resurrect 2001 or 2002.
        String stale2001 = publicArticleEventJson(
                "evt-2001-late-v2", "ArticleContentChanged", 2001L, 2L,
                "Resurrected 2001", "Sum", "Body", 9L, 2L);
        String stale2002 = publicArticleEventJson(
                "evt-2002-late-v1", "ArticlePublished", 2002L, 1L,
                "Resurrected 2002", "Sum", "Body", 9L, 2L);
        assertThat(ingestionService.ingestStreamMessage("late-1", Map.of("event", stale2001)))
                .isEqualTo(IngestionOutcome.STALE_IGNORED);
        assertThat(ingestionService.ingestStreamMessage("late-2", Map.of("event", stale2002)))
                .isEqualTo(IngestionOutcome.STALE_IGNORED);
        assertThat(consumerRepository.findProjection(1L, 2001L).orElseThrow().visibility())
                .isEqualTo(ArticleVisibility.WITHDRAWN);
        assertThat(consumerRepository.findProjection(1L, 2002L).orElseThrow().visibility())
                .isEqualTo(ArticleVisibility.DELETED);
    }

    // =========================================================================
    // Criterion 3: Abandoned pre-switch FAILED & HTTP 410 EXPIRED_SUPERSEDED
    // keep active g1 serving; successful cutover promotes candidate barriers,
    // preserves prior delete barriers, counter/content independence, and old-gen
    // worker isolation.
    // =========================================================================
    @Test
    void abandonedRebuildsKeepOriginalGenServingAndSuccessfulCutoverPromotesBarriersAndIsolatesOldGen() throws Exception {
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-3001-v1", "ArticlePublished", 3001L, 1L,
                "Story3001 BaseV1", "Sum", "Body V1", 10L, 1L)));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", hiddenArticleEventJson(
                "evt-3002-d5", "ArticleDeleted", 3002L, 5L, "DELETED")));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-3003-v1", "ArticlePublished", 3003L, 1L,
                "Story3003 CounterBase", "Sum", "Body V1", 100L, 10L)));
        streamConsumer.pollOnce();
        indexWorker.processOnce();
        visibilityTable.put("3001", new VisibilityDecision("3001", "1", "PUBLIC", "READABLE"));
        visibilityTable.put("3003", new VisibilityDecision("3003", "1", "PUBLIC", "READABLE"));

        // --- Sub-scenario A: Candidate g2 imports 3001 PUBLIC v2, then FAILS pre-switch ---
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(
                        3001L, 2L, "Story3001 FailedG2V2", "Sum", "Body V2",
                        10L, "Tech", List.of("Spring"), 10L, 1L, "2026-10-01T00:00:00Z")
        ));
        RebuildJobRecord jobG2 = rebuildService.startRebuild("req-r6-fail-g2");
        jobG2 = advanceUntilPhase(rebuildService, jobG2.jobId(), RebuildPhase.SWITCHING_ALIAS);
        assertThat(jobG2.candidateGeneration()).isEqualTo(2L);

        esStub.setInterceptor((req, def) -> {
            if ("POST".equalsIgnoreCase(req.method()) && "/_aliases".equals(req.path())
                    && req.body().contains(G2)) {
                return StatefulElasticsearchStub.StubResponse.status(400, "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"pre_switch_reject\"},\"status\":400}");
            }
            return def.get();
        });
        for (int i = 0; i < 16 && jobG2.phase() != RebuildPhase.FAILED; i++) {
            jobG2 = rebuildService.advanceJob(jobG2.jobId()).job();
        }
        esStub.clearInterceptor();
        assertThat(jobG2.phase()).isEqualTo(RebuildPhase.FAILED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);

        // Active g1 MUST continue ingesting, indexing, and serving 3001 PUBLIC v2!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-3001-v2", "ArticleContentChanged", 3001L, 2L,
                "Story3001 FailedG2V2", "Sum", "Body V2", 11L, 2L)));
        streamConsumer.pollOnce();
        indexWorker.processOnce();
        assertThat(consumerRepository.findInboxStatus("evt-3001-v2")).contains("APPLIED");
        visibilityTable.put("3001", new VisibilityDecision("3001", "2", "PUBLIC", "READABLE"));
        PublicArticleSearchService publicSearch = createPublicSearchService();
        assertThat(publicSearch.search("FailedG2V2", 1, 10, "relevance", "req-r6-after-fail").rows())
                .extracting(r -> r.id()).containsExactly(3001L);

        // --- Sub-scenario B: Candidate g3 imports 3001 PUBLIC v3, then hits HTTP 410 -> EXPIRED_SUPERSEDED -> g4 ---
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(
                        3001L, 3L, "Story3001 ExpiredG3V3", "Sum", "Body V3",
                        10L, "Tech", List.of("Spring"), 15L, 3L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(
                        3003L, 2L, "Story3003 CutoverV2", "Sum", "Body V2",
                        10L, "Tech", List.of("Spring"), 50L, 5L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(
                        3004L, 1L, "Story3004 ToBeDeleted", "Sum", "Body V1",
                        10L, "Tech", List.of("Spring"), 1L, 1L, "2026-10-01T00:00:00Z")
        ));
        RebuildJobRecord jobG3 = rebuildService.startRebuild("req-r6-expire-g3");
        jobG3 = advanceUntilPhase(rebuildService, jobG3.jobId(), RebuildPhase.INCREMENTAL_CATCHUP);
        assertThat(jobG3.candidateGeneration()).isEqualTo(3L);
        snapshotStub.expireChangeCursor(jobG3.incrementalCursor());

        SearchRebuildService.StepOutcome expiredOutcome = rebuildService.advanceJob(jobG3.jobId());
        assertThat(expiredOutcome.status()).isEqualTo(SearchRebuildService.StepStatus.EXPIRED_410_RESTARTED);
        assertThat(rebuildService.findJob(jobG3.jobId()).orElseThrow().phase())
                .isEqualTo(RebuildPhase.EXPIRED_SUPERSEDED);
        RebuildJobRecord jobG4 = expiredOutcome.job();
        assertThat(jobG4.candidateGeneration()).isEqualTo(4L);
        assertThat(jobG4.phase()).isEqualTo(RebuildPhase.INIT);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G1);

        // Active g1 MUST still ingest, index, and serve 3001 PUBLIC v3 even after g3 expired!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-3001-v3", "ArticleContentChanged", 3001L, 3L,
                "Story3001 ExpiredG3V3", "Sum", "Body V3", 15L, 3L)));
        streamConsumer.pollOnce();
        indexWorker.processOnce();
        assertThat(consumerRepository.findInboxStatus("evt-3001-v3")).contains("APPLIED");
        visibilityTable.put("3001", new VisibilityDecision("3001", "3", "PUBLIC", "READABLE"));
        assertThat(publicSearch.search("ExpiredG3V3", 1, 10, "relevance", "req-r6-after-410").rows())
                .extracting(r -> r.id()).containsExactly(3001L);

        // --- Sub-scenario C: Candidate g4 catches up 3004 DELETED v2 & 3003 counterRev=12, then cuts over ---
        jobG4 = advanceUntilPhase(rebuildService, jobG4.jobId(), RebuildPhase.INCREMENTAL_CATCHUP);
        snapshotStub.appendCommitBatch(List.of(
                MAPPER.readTree(hiddenArticleEventJson("evt-3004-d2", "ArticleDeleted", 3004L, 2L, "DELETED")),
                MAPPER.readTree(counterChangedEventJson("evt-3003-c12", 3003L, 12L, 321L))
        ));
        jobG4 = advanceUntilPhase(rebuildService, jobG4.jobId(), RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ALIAS)).containsExactly(G4);
        IndexGenerationState activeAfterCutover = consumerRepository.findGenerationState(ALIAS).orElseThrow();
        assertThat(activeAfterCutover.activeGeneration()).isEqualTo(4L);
        assertThat(activeAfterCutover.previousGeneration()).isEqualTo(1L);

        // 1) Atomically promoted candidate g4 barriers AND preserved prior g1 delete barrier!
        assertThat(consumerRepository.findVersionBarrier(3002L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(5L, ArticleVisibility.DELETED);
        assertThat(consumerRepository.findVersionBarrier(3004L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(2L, ArticleVisibility.DELETED);
        assertThat(consumerRepository.findVersionBarrier(3001L).orElseThrow().maxContentRevision()).isEqualTo(3L);

        // 2) Delete barriers block late upserts on new active gen g4 (both prior g1 3002 and g4 catchup 3004).
        assertThat(ingestionService.ingestStreamMessage("cut-late-3002", Map.of("event",
                publicArticleEventJson("evt-3002-late-v4", "ArticleContentChanged", 3002L, 4L,
                        "Zombie 3002", "Sum", "Body", 1L, 1L))))
                .isEqualTo(IngestionOutcome.STALE_IGNORED);
        assertThat(ingestionService.ingestStreamMessage("cut-late-3004", Map.of("event",
                publicArticleEventJson("evt-3004-late-v2", "ArticleContentChanged", 3004L, 2L,
                        "Zombie 3004", "Sum", "Body", 1L, 1L))))
                .isEqualTo(IngestionOutcome.STALE_IGNORED);

        // 3) Independent content vs counter dimensions on g4:
        assertThat(ingestionService.ingestStreamMessage("cut-c11", Map.of("event",
                counterChangedEventJson("evt-3003-c11", 3003L, 11L, 111L))))
                .isEqualTo(IngestionOutcome.STALE_IGNORED);
        assertThat(ingestionService.ingestStreamMessage("cut-u3", Map.of("event",
                publicArticleEventJson("evt-3003-u3", "ArticleContentChanged", 3003L, 3L,
                        "Story3003 NewContentKeepCounter", "Sum V3", "Body V3", 9L, 1L))))
                .isEqualTo(IngestionOutcome.APPLIED);
        ArticleProjectionRecord proj3003 = consumerRepository.findProjection(4L, 3003L).orElseThrow();
        assertThat(proj3003.contentRevision()).isEqualTo(3L);
        assertThat(proj3003.counterRevision()).isEqualTo(12L);
        assertThat(proj3003.viewCount()).isEqualTo(321L);
        indexWorker.processOnce();

        // 4) Late old-generation (g1) worker write isolation: writing stale doc to G1 cannot pollute G4 or search!
        txTemplate.executeWithoutResult(status -> consumerRepository.upsertPendingIndexTask(
                1L, G1, 3001L, 99L, SearchIndexOperation.UPSERT,
                "{\"id\":3001,\"title\":\"StaleOldGenWrite\",\"summary\":\"Old\",\"content\":\"Old\","
                        + "\"searchable\":true,\"sourceRevision\":\"1\",\"viewCount\":1}",
                3, clock.millis()));
        // Online worker only claims active generation (g4); even if a late g1 worker flushes to G1, G4 is isolated.
        indexWorker.processOnce();
        indexWorker.processCandidateGenerationOnce(1L, G1);
        assertThat(esStub.getRefreshedDocument(G4, "3001").sourceJson()).contains("Story3001 ExpiredG3V3");
        visibilityTable.put("3003", new VisibilityDecision("3003", "3", "PUBLIC", "READABLE"));
        assertThat(publicSearch.search("StaleOldGenWrite", 1, 10, "relevance", "req-r6-iso").rows()).isEmpty();
        assertThat(publicSearch.search("NewContentKeepCounter", 1, 10, "relevance", "req-r6-g4-hit").rows())
                .extracting(r -> r.id()).containsExactly(3003L);
    }

    // =========================================================================
    // Criterion 4 (Runtime Self-Healing): Legacy pre-R6 polluted barrier rows
    // left by an unactivated candidate are healed automatically on stream
    // ingestion, post-cutover cold-visibility guard, and admin single-article
    // sync, while preserving valid activated & standalone delete barriers.
    // =========================================================================
    @Test
    void runtimeSelfHealingClearsLegacyPollutedBarriersWhilePreservingValidDeleteBarriers() throws Exception {
        long nowMs = clock.millis();
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-4001-v1", "ArticlePublished", 4001L, 1L, "Art4001 V1", "S", "B", 1L, 1L)));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-4002-v1", "ArticlePublished", 4002L, 1L, "Art4002 V1", "S", "B", 1L, 1L)));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", hiddenArticleEventJson(
                "evt-4003-d5", "ArticleDeleted", 4003L, 5L, "DELETED")));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-4007-v1", "ArticlePublished", 4007L, 1L, "Art4007 V1", "S", "B", 1L, 1L)));
        streamConsumer.pollOnce();
        indexWorker.processOnce();

        // Simulate legacy pre-R6 abandoned candidate g2 (FAILED) that polluted search_article_version_barrier!
        jdbcTemplate.update("""
                INSERT INTO search_rebuild_job (
                    job_id, alias_name, active_lock_key, client_request_id,
                    snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                    last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                    source_generation, source_physical_index, candidate_generation, candidate_physical_index,
                    phase, lease_owner, lease_until_ms, lease_generation,
                    alias_switch_uncertain, switch_attempts, created_at_ms, updated_at_ms
                ) VALUES (
                    'rb-legacy-polluted-g2', ?, NULL, 'req-legacy-g2',
                    1, 1, 4, 1, 1, 1,
                    1, ?, 2, ?,
                    'FAILED', 'old-inst', ?, 1,
                    0, 0, ?, ?
                )
                """, ALIAS, G1, G2, nowMs, nowMs, nowMs);

        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms
                ) VALUES
                    (2, 4001, 1, 2, 'PUBLIC', 1, 10, 1, 'Art4001 V2', 'S', 'B', ?),
                    (2, 4002, 1, 2, 'DELETED', 0, 0, 1, NULL, NULL, NULL, ?),
                    (2, 4004, 1, 1, 'PUBLIC', 0, 1, 1, 'Art4004 BrandNewV1', 'S', 'B', ?),
                    (2, 4007, 1, 2, 'DELETED', 0, 0, 1, NULL, NULL, NULL, ?)
                """, nowMs, nowMs, nowMs, nowMs);
        jdbcTemplate.update("""
                INSERT INTO search_rebuild_event_log (
                    candidate_generation, event_id, commit_sequence, event_type,
                    aggregate_type, aggregate_id, source_revision, status, applied_at_ms
                ) VALUES
                    (2, 'evt-rb-g2-4002-d2', 1, 'ArticleDeleted', 'ARTICLE', '4002', 2, 'APPLIED', ?),
                    (2, 'evt-rb-g2-4007-d2', 2, 'ArticleDeleted', 'ARTICLE', '4007', 2, 'APPLIED', ?)
                """, nowMs, nowMs);

        jdbcTemplate.update("""
                UPDATE search_article_version_barrier
                SET max_content_revision = 2, last_visibility = 'PUBLIC', updated_at_ms = ?
                WHERE article_id = 4001
                """, nowMs);
        jdbcTemplate.update("""
                UPDATE search_article_version_barrier
                SET max_content_revision = 2, last_visibility = 'DELETED', updated_at_ms = ?
                WHERE article_id = 4002
                """, nowMs);
        jdbcTemplate.update("""
                INSERT INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
                VALUES (4004, 1, 'PUBLIC', ?),
                       (4005, 7, 'DELETED', ?)
                """, nowMs, nowMs);
        jdbcTemplate.update("""
                UPDATE search_article_version_barrier
                SET max_content_revision = 2, last_visibility = 'DELETED', updated_at_ms = ?
                WHERE article_id = 4007
                """, nowMs);

        // 1) Stream ingestion heals 4001 (PUBLIC v2), 4002 (DELETED v2), and 4004 (PUBLIC v1) on active g1!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-4001-v2", "ArticleContentChanged", 4001L, 2L, "Art4001 HealedV2", "S2", "B2", 2L, 2L)));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", hiddenArticleEventJson(
                "evt-4002-d2", "ArticleDeleted", 4002L, 2L, "DELETED")));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-4004-v1", "ArticlePublished", 4004L, 1L, "Art4004 HealedBrandNewV1", "S1", "B1", 1L, 1L)));
        // Valid delete barriers 4003 (g1 DELETED v5) and 4005 (standalone DELETED v7) MUST still block stale events!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-4003-stale-v4", "ArticleContentChanged", 4003L, 4L, "Zombie 4003", "S", "B", 1L, 1L)));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-4005-stale-v6", "ArticleContentChanged", 4005L, 6L, "Zombie 4005", "S", "B", 1L, 1L)));
        streamConsumer.pollOnce();
        indexWorker.processOnce();

        assertThat(consumerRepository.findInboxStatus("evt-4001-v2")).contains("APPLIED");
        assertThat(consumerRepository.findInboxStatus("evt-4002-d2")).contains("APPLIED");
        assertThat(consumerRepository.findInboxStatus("evt-4004-v1")).contains("APPLIED");
        assertThat(consumerRepository.findInboxStatus("evt-4003-stale-v4")).contains("STALE_IGNORED");
        assertThat(consumerRepository.findInboxStatus("evt-4005-stale-v6")).contains("STALE_IGNORED");

        assertThat(esStub.getRefreshedDocument(G1, "4001").sourceJson()).contains("Art4001 HealedV2");
        assertThat(MAPPER.readTree(esStub.getRefreshedDocument(G1, "4002").sourceJson()).path("searchable").asBoolean())
                .isFalse();
        assertThat(esStub.getRefreshedDocument(G1, "4004").sourceJson()).contains("Art4004 HealedBrandNewV1");
        assertThat(consumerRepository.findVersionBarrier(4003L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(5L, ArticleVisibility.DELETED);
        assertThat(consumerRepository.findVersionBarrier(4005L).orElseThrow())
                .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                .containsExactly(7L, ArticleVisibility.DELETED);

        // 2) SearchAdminService single-article sync heals polluted 4007 (PUBLIC v2) while still blocking 4003 & 4005!
        AdminPrincipal admin = new AdminPrincipal("admin-1", "root", Set.of("search:ops"));
        snapshotStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                4007L, 2L, "Art4007 AdminSyncedV2", "S2", "B2",
                10L, "Tech", List.of("Spring"), 5L, 2L, "2026-10-01T00:00:00Z"));
        snapshotStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                4003L, 4L, "Stale4003", "S", "B",
                10L, "Tech", List.of("Spring"), 5L, 1L, "2026-10-01T00:00:00Z"));
        snapshotStub.putCurrentPublicArticle(LoopbackContentSnapshotStub.snapshotItem(
                4005L, 6L, "Stale4005", "S", "B",
                10L, "Tech", List.of("Spring"), 5L, 1L, "2026-10-01T00:00:00Z"));

        Map<String, Object> sync4007 = adminService.synchronizeArticle(admin, "4007", "req-sync-4007");
        assertThat(sync4007.get("action")).isEqualTo("UPSERT_SYNCED");
        assertThat(esStub.getRefreshedDocument(G1, "4007").sourceJson()).contains("Art4007 AdminSyncedV2");

        Map<String, Object> sync4003 = adminService.synchronizeArticle(admin, "4003", "req-sync-4003");
        assertThat(sync4003.get("action")).isEqualTo("STALE_BLOCKED_BY_BARRIER");
        Map<String, Object> sync4005 = adminService.synchronizeArticle(admin, "4005", "req-sync-4005");
        assertThat(sync4005.get("action")).isEqualTo("STALE_BLOCKED_BY_BARRIER");

        // 3) Post-cutover cold-article visibility guard (require_cold_visibility_guard = 1):
        jdbcTemplate.update("""
                UPDATE search_index_generation SET require_cold_visibility_guard = 1 WHERE alias_name = ?
                """, ALIAS);
        jdbcTemplate.update("""
                INSERT INTO search_article_projection (
                    index_generation, article_id, has_content, content_revision, visibility,
                    counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms
                ) VALUES (2, 4006, 1, 2, 'PUBLIC', 0, 1, 1, 'Art4006 ColdPollutedV2', 'S', 'B', ?)
                """, nowMs);
        jdbcTemplate.update("""
                INSERT INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
                VALUES (4006, 2, 'PUBLIC', ?)
                """, nowMs);
        visibilityTable.put("4006", new VisibilityDecision("4006", "2", "PUBLIC", "READABLE"));
        int callsBefore = visibilityCalls.get();

        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", publicArticleEventJson(
                "evt-4006-v2", "ArticlePublished", 4006L, 2L, "Art4006 ColdHealedV2", "S2", "B2", 3L, 1L)));
        streamConsumer.pollOnce();
        indexWorker.processOnce();

        assertThat(visibilityCalls.get()).isGreaterThan(callsBefore);
        assertThat(consumerRepository.findInboxStatus("evt-4006-v2")).contains("APPLIED");
        assertThat(esStub.getRefreshedDocument(G1, "4006").sourceJson()).contains("Art4006 ColdHealedV2");
    }

    private RebuildJobRecord advanceUntilPhase(SearchRebuildService svc, String jobId, RebuildPhase target) {
        RebuildJobRecord current = svc.findJob(jobId).orElseThrow();
        for (int i = 0; i < 20 && current.phase() != target; i++) {
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
        root.put("occurredAt", "2026-11-27T08:00:00Z");
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

    private static String hiddenArticleEventJson(
            String eventId,
            String eventType,
            long articleId,
            long sourceRevision,
            String visibility) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", eventType);
        root.put("schemaVersion", 2);
        root.put("producer", "knode-content");
        root.put("occurredAt", "2026-11-27T08:00:00Z");
        root.put("aggregateType", "ARTICLE");
        root.put("visibility", visibility);
        root.putNull("publicationFactId");
        root.put("aggregateId", Long.toString(articleId));
        root.put("sourceRevision", Long.toString(sourceRevision));
        ObjectNode payload = root.putObject("payload");
        payload.put("articleId", Long.toString(articleId));
        return MAPPER.writeValueAsString(root);
    }

    private static String counterChangedEventJson(
            String eventId,
            long articleId,
            long counterRevision,
            long viewCount) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", "ArticleCounterChanged");
        root.put("schemaVersion", 1);
        root.put("producer", "knode-content");
        root.put("occurredAt", "2026-11-27T08:00:00Z");
        root.put("aggregateType", "ARTICLE_COUNTER");
        root.put("visibility", "PUBLIC");
        root.putNull("publicationFactId");
        root.put("aggregateId", Long.toString(articleId));
        root.put("sourceRevision", Long.toString(counterRevision));
        ObjectNode payload = root.putObject("payload");
        payload.put("articleId", Long.toString(articleId));
        payload.put("viewCount", Long.toString(viewCount));
        payload.put("counterRevision", Long.toString(counterRevision));
        return MAPPER.writeValueAsString(root);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
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
