package com.knode.search.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.cache.ArticleSearchCache;
import com.knode.search.cache.SearchCacheInvalidator;
import com.knode.search.client.ContentVisibilityClient;
import com.knode.search.client.VisibilityDecision;
import com.knode.search.config.SearchCacheProperties;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.config.SearchQueryProperties;
import com.knode.search.config.SearchRuntimeConfig;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.event.ContentEventParser;
import com.knode.search.consumer.indexer.ElasticsearchArticleIndexWriter;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.ContentEventIngestionService;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.consumer.stream.ContentStreamConsumer;
import com.knode.search.consumer.stream.LettuceContentStreamClient;
import com.knode.search.consumer.stream.StreamRecord;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.PageResult;
import com.knode.search.repository.ElasticsearchArticleSearchRepository;
import com.knode.search.service.PublicArticleSearchService;
import com.knode.search.service.SearchQueryPlanner;
import com.knode.search.support.LoopbackRedisStub;
import com.knode.search.support.StatefulElasticsearchStub;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SearchConsumerR1RegressionsTest {
    private static final String ENV = "it-r1-consumer";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String GROUP = "knode-search-v1";
    private static final String ES_INDEX = "knode_it_r1_articles_search";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HikariDataSource dataSource;
    private MutableClock clock;
    private LoopbackRedisStub streamRedis;
    private LoopbackRedisStub cacheRedis;
    private LettuceConnectionFactory cacheRedisFactory;
    private StatefulElasticsearchStub esStub;
    private ElasticsearchClient esClient;

    private SearchConsumerProperties consumerProps;
    private SearchConsumerRepository repository;
    private ContentEventIngestionService ingestionService;
    private LettuceContentStreamClient streamClientA;
    private LettuceContentStreamClient streamClientB;
    private ContentStreamConsumer consumerB;
    private SearchCacheInvalidator cacheInvalidator;
    private SearchIndexTaskWorker indexWorker;
    private PublicArticleSearchService publicSearchService;
    private Map<String, VisibilityDecision> visibilityTable;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "search_r1_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");
        hc.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(hc);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/search-consumer-schema.sql"));
        populator.execute(dataSource);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        txTemplate.setTimeout(5);

        clock = new MutableClock(Instant.parse("2026-11-26T08:00:00Z"));
        streamRedis = new LoopbackRedisStub();
        streamRedis.createGroup(STREAM_KEY, GROUP);

        cacheRedis = new LoopbackRedisStub();
        SearchCacheProperties cacheProps = new SearchCacheProperties();
        cacheProps.setEnabled(true);
        cacheProps.setAdmissionThreshold(2);
        cacheProps.setAdmissionTtlSeconds(60);
        cacheProps.setResultTtlSeconds(60);
        cacheProps.setEmptyTtlSeconds(30);
        cacheProps.setJitterSeconds(0);
        cacheProps.getRedis().setHost("127.0.0.1");
        cacheProps.getRedis().setPort(cacheRedis.port());
        cacheProps.getRedis().setConnectTimeoutMs(400);
        cacheProps.getRedis().setCommandTimeoutMs(400);

        SearchRuntimeConfig runtimeConfig = new SearchRuntimeConfig();
        cacheRedisFactory = runtimeConfig.searchCacheRedisConnectionFactory(cacheProps);
        cacheRedisFactory.afterPropertiesSet();
        StringRedisTemplate cacheRedisTemplate = runtimeConfig.searchCacheRedisTemplate(cacheRedisFactory);

        esStub = new StatefulElasticsearchStub();
        SearchElasticsearchProperties esProps = new SearchElasticsearchProperties();
        esProps.setUrl(esStub.baseUrl());
        esProps.setIndex(ES_INDEX);
        esProps.setConnectTimeoutMs(500);
        esProps.setConnectionRequestTimeoutMs(500);
        esProps.setResponseTimeoutMs(2000);
        SearchQueryProperties queryProps = new SearchQueryProperties();
        queryProps.setTotalTimeoutMs(3000);
        esClient = runtimeConfig.elasticsearchClient(esProps, queryProps);

        consumerProps = new SearchConsumerProperties();
        consumerProps.setEnabled(true);
        consumerProps.getStream().setEnabled(true);
        consumerProps.getStream().setGroup(GROUP);
        consumerProps.getStream().setBatchSize(2);
        consumerProps.getStream().setBlockTimeoutMs(50);
        consumerProps.getStream().setMinIdleMs(1000);
        consumerProps.getStream().getRedis().setHost("127.0.0.1");
        consumerProps.getStream().getRedis().setPort(streamRedis.port());
        consumerProps.getStream().getRedis().setConnectTimeoutMs(400);
        consumerProps.getStream().getRedis().setCommandTimeoutMs(800);

        consumerProps.getIndexer().setEnabled(true);
        consumerProps.getIndexer().setBatchSize(10);
        consumerProps.getIndexer().setLeaseSeconds(30);
        consumerProps.getIndexer().setMaxAttempts(3);
        consumerProps.getIndexer().setBaseRetryDelayMs(1000);
        consumerProps.getIndexer().setMaxRetryDelayMs(10000);
        consumerProps.getIndexer().setRefreshPolicy("wait_for");
        consumerProps.getIndexer().setCacheCompensationBatchSize(10);
        consumerProps.getIndexer().setCacheCompensationBaseDelayMs(1000);
        consumerProps.getIndexer().setCacheCompensationMaxDelayMs(10000);

        repository = new SearchConsumerRepository(jdbcTemplate, MAPPER);
        ingestionService = new ContentEventIngestionService(
                new ContentEventParser(), repository, txTemplate, consumerProps, MAPPER, clock);

        streamClientA = new LettuceContentStreamClient(
                STREAM_KEY, GROUP, "knode-search-inst-A", consumerProps.getStream().getRedis());
        streamClientB = new LettuceContentStreamClient(
                STREAM_KEY, GROUP, "knode-search-inst-B", consumerProps.getStream().getRedis());
        consumerB = new ContentStreamConsumer(streamClientB, ingestionService, consumerProps);

        ObjectProvider<StringRedisTemplate> cacheProvider = singleObjectProvider(cacheRedisTemplate);
        cacheInvalidator = new SearchCacheInvalidator(cacheProvider, ENV);
        ElasticsearchArticleIndexWriter indexWriter =
                new ElasticsearchArticleIndexWriter(esClient, esProps, consumerProps, MAPPER);
        indexWorker = new SearchIndexTaskWorker(
                repository, txTemplate, indexWriter, cacheInvalidator, consumerProps, clock);

        SearchMetrics metrics = new SearchMetrics(new SimpleMeterRegistry());
        ArticleSearchCache articleSearchCache = new ArticleSearchCache(cacheProvider, MAPPER, cacheProps, metrics, ENV);
        SearchQueryPlanner planner = new SearchQueryPlanner(queryProps, cacheProps, esProps, ENV);
        ElasticsearchArticleSearchRepository searchRepo = new ElasticsearchArticleSearchRepository(esClient, esProps);
        visibilityTable = new ConcurrentHashMap<>();
        ContentVisibilityClient visibilityClient = (ids, budget, requestId) -> {
            Map<String, VisibilityDecision> out = new ConcurrentHashMap<>();
            for (String id : ids) {
                VisibilityDecision d = visibilityTable.get(id);
                if (d != null) {
                    out.put(id, d);
                }
            }
            return out;
        };
        publicSearchService = new PublicArticleSearchService(
                planner, articleSearchCache, searchRepo, visibilityClient, queryProps, metrics);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (streamClientA != null) {
            streamClientA.close();
        }
        if (streamClientB != null) {
            streamClientB.close();
        }
        if (cacheRedisFactory != null) {
            cacheRedisFactory.destroy();
        }
        if (esClient != null) {
            esClient.close();
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
    void reclaimsIdlePendingBeyondHeadBatchWithoutDisturbingActiveHeadEntriesAndWrapsCursor() {
        consumerProps.getStream().setBatchSize(2);
        consumerProps.getStream().setMinIdleMs(1000);

        List<String> recordIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long articleId = 9101L + i;
            String eventJson = publicArticleEventJson(
                    "evt-pel-" + articleId, "ArticlePublished", 2, articleId, 1L,
                    "PEL Article " + articleId, "Summary", "Body", 1L, 1L);
            recordIds.add(streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", eventJson)));
        }

        // Instance A reads all 10 entries (5 batches of size 2) and holds them in PEL without XACK.
        List<StreamRecord> delivered = streamClientA.readNew(10, 50);
        assertThat(delivered).hasSize(10);
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isEqualTo(10);

        // Head 4 batches (indices 0..7) stay active (idle < minIdleMs); tail batch (indices 8..9) ages past minIdleMs.
        streamRedis.agePendingEntry(STREAM_KEY, GROUP, recordIds.get(8), 2000L);
        streamRedis.agePendingEntry(STREAM_KEY, GROUP, recordIds.get(9), 2000L);

        // Bounded polling on Instance B traverses past the 4 active head batches via persisted cursor and claims batch 5.
        int totalReclaimed = 0;
        int totalAcked = 0;
        for (int poll = 0; poll < 3 && totalReclaimed < 2; poll++) {
            ContentStreamConsumer.PollSummary summary = consumerB.pollOnce();
            totalReclaimed += summary.reclaimedCount();
            totalAcked += summary.ackedCount();
        }
        assertThat(totalReclaimed).isEqualTo(2);
        assertThat(totalAcked).isEqualTo(2);
        assertThat(repository.findProjection(9109L)).isPresent();
        assertThat(repository.findProjection(9110L)).isPresent();
        assertThat(repository.findInboxStatus("evt-pel-9109")).contains("APPLIED");
        assertThat(repository.findInboxStatus("evt-pel-9110")).contains("APPLIED");

        // Active head entries 0..7 MUST remain untouched in PEL and still owned by Instance A.
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isEqualTo(8);
        for (int i = 0; i < 8; i++) {
            assertThat(streamRedis.pendingConsumerOf(STREAM_KEY, GROUP, recordIds.get(i)))
                    .isEqualTo("knode-search-inst-A");
            assertThat(repository.findProjection(9101L + i)).isEmpty();
        }

        // Now age head record 0 (article 9101) past minIdleMs -> cursor wraps back to head and reclaims it.
        streamRedis.agePendingEntry(STREAM_KEY, GROUP, recordIds.get(0), 2000L);
        ContentStreamConsumer.PollSummary wrapped = consumerB.pollOnce();
        assertThat(wrapped.reclaimedCount()).isEqualTo(1);
        assertThat(wrapped.ackedCount()).isEqualTo(1);
        assertThat(repository.findProjection(9101L)).isPresent();
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isEqualTo(7);
        for (int i = 1; i < 8; i++) {
            assertThat(streamRedis.pendingConsumerOf(STREAM_KEY, GROUP, recordIds.get(i)))
                    .isEqualTo("knode-search-inst-A");
        }

        Set<String> forbiddenOps = Set.of("XGROUP", "XTRIM", "XDEL", "KEYS", "SCAN", "FLUSHDB", "FLUSHALL");
        for (List<String> cmd : streamRedis.recordedCommands()) {
            assertThat(forbiddenOps).doesNotContain(cmd.get(0).toUpperCase(Locale.ROOT));
        }
    }

    @Test
    void holdsTaskRetryableAndPreservesCacheEpochWhen409WrittenDocIsNotYetRefreshedOnSearchPath() throws Exception {
        // Warm cache epoch to "1" via an already-indexed baseline article 9200.
        ingestionService.ingestStreamMessage("s-9200-1", Map.of("event",
                publicArticleEventJson("evt-9200-v1", "ArticlePublished", 2, 9200L, 1L,
                        "Baseline Searchable", "Base", "Base body", 5L, 1L)));
        indexWorker.processOnce();
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");

        // === Phase 1: New document 9201 v1 written to ES but NOT yet refreshed ===
        esStub.setAutoRefresh(false);
        ingestionService.ingestStreamMessage("s-9201-1", Map.of("event",
                publicArticleEventJson("evt-9201-v1", "ArticlePublished", 2, 9201L, 1L,
                        "RefreshLag Article V1", "LagOne", "Lag body one", 10L, 1L)));

        AtomicBoolean timeoutFirstPutV1 = new AtomicBoolean(true);
        esStub.setInterceptor((req, delegate) -> {
            if ("PUT".equals(req.method()) && req.path().endsWith("/_doc/9201")
                    && hasVersionParam(req.query(), "1")
                    && timeoutFirstPutV1.compareAndSet(true, false)) {
                // Write lands in ES writtenDocuments (NOT refreshedDocuments), then client sees timeout.
                delegate.get();
                return StatefulElasticsearchStub.StubResponse.status(
                        504, "{\"error\":\"wait_for_refresh_timeout\"}");
            }
            return delegate.get();
        });

        // Attempt 1: times out waiting for refresh -> task stays PENDING, epoch stays "1".
        indexWorker.processOnce();
        SearchIndexTaskRecord afterTimeout1 = repository.findIndexTask(9201L).orElseThrow();
        assertThat(afterTimeout1.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(afterTimeout1.attempts()).isEqualTo(1);
        assertThat(afterTimeout1.esAppliedRevision()).isZero();
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");

        // Contrast realtime GET vs refreshed _search: realtime GET sees v1, refreshed search path sees nothing!
        GetResponse<JsonNode> realtimeGetV1 = esClient.get(
                g -> g.index(ES_INDEX).id("9201"), JsonNode.class);
        assertThat(realtimeGetV1.found()).isTrue();
        assertThat(realtimeGetV1.version()).isEqualTo(1L);
        assertThat(esStub.getRefreshedDocument(ES_INDEX, "9201")).isNull();

        // Attempt 2 (still unrefreshed): PUT triggers ES 409; search-path verification sees 0 hits ->
        // MUST stay retryable PENDING and MUST NOT advance cache epoch!
        esStub.clearInterceptor();
        clock.advance(Duration.ofSeconds(2));
        indexWorker.processOnce();
        SearchIndexTaskRecord unrefreshed409 = repository.findIndexTask(9201L).orElseThrow();
        assertThat(unrefreshed409.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(unrefreshed409.attempts()).isEqualTo(2);
        assertThat(unrefreshed409.esAppliedRevision()).isZero();
        assertThat(unrefreshed409.cacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.NONE);
        assertThat(unrefreshed409.lastError()).contains("ES_409_NOT_YET_SEARCH_VISIBLE");
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("1");

        // Trigger ES refresh -> Attempt 3 hits 409, search-path verification sees refreshed v1 -> DONE & epoch -> "2"!
        esStub.refresh(ES_INDEX);
        clock.advance(Duration.ofSeconds(5));
        SearchIndexTaskWorker.WorkerCycleSummary recoveredV1 = indexWorker.processOnce();
        assertThat(recoveredV1.appliedCount()).isEqualTo(1);
        SearchIndexTaskRecord doneV1 = repository.findIndexTask(9201L).orElseThrow();
        assertThat(doneV1.status()).isEqualTo(SearchIndexTaskStatus.DONE);
        assertThat(doneV1.esAppliedRevision()).isEqualTo(1L);
        assertThat(doneV1.cacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("2");

        // === Phase 2: Existing refreshed v1 updated to v2 unrefreshed + timeout ===
        ingestionService.ingestStreamMessage("s-9201-2", Map.of("event",
                publicArticleEventJson("evt-9201-v2", "ArticleContentChanged", 2, 9201L, 2L,
                        "RefreshLag Article V2", "LagTwo", "Lag body two", 20L, 2L)));

        AtomicBoolean timeoutFirstPutV2 = new AtomicBoolean(true);
        esStub.setInterceptor((req, delegate) -> {
            if ("PUT".equals(req.method()) && req.path().endsWith("/_doc/9201")
                    && hasVersionParam(req.query(), "2")
                    && timeoutFirstPutV2.compareAndSet(true, false)) {
                delegate.get();
                return StatefulElasticsearchStub.StubResponse.status(
                        504, "{\"error\":\"wait_for_refresh_timeout\"}");
            }
            return delegate.get();
        });

        indexWorker.processOnce();
        assertThat(repository.findIndexTask(9201L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("2");

        // Realtime GET already sees v2 while refreshed _search still sees stale v1!
        GetResponse<JsonNode> realtimeGetV2 = esClient.get(
                g -> g.index(ES_INDEX).id("9201"), JsonNode.class);
        assertThat(realtimeGetV2.found()).isTrue();
        assertThat(realtimeGetV2.version()).isEqualTo(2L);
        assertThat(esStub.getRefreshedDocument(ES_INDEX, "9201").version()).isEqualTo(1L);

        // Retry hits 409; search path sees stale version 1 < 2 -> stays PENDING, cache epoch stays "2"!
        esStub.clearInterceptor();
        clock.advance(Duration.ofSeconds(2));
        indexWorker.processOnce();
        SearchIndexTaskRecord staleSearch409 = repository.findIndexTask(9201L).orElseThrow();
        assertThat(staleSearch409.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(staleSearch409.attempts()).isEqualTo(2);
        assertThat(staleSearch409.esAppliedRevision()).isEqualTo(1L);
        assertThat(staleSearch409.lastError()).contains("ES_409_SEARCH_STALE_VERSION:1");
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("2");

        // After ES refresh, retry hits 409, search path sees v2 -> DONE, cache epoch advances to "3", search returns v2!
        esStub.refresh(ES_INDEX);
        clock.advance(Duration.ofSeconds(5));
        SearchIndexTaskWorker.WorkerCycleSummary recoveredV2 = indexWorker.processOnce();
        assertThat(recoveredV2.appliedCount()).isEqualTo(1);
        SearchIndexTaskRecord doneV2 = repository.findIndexTask(9201L).orElseThrow();
        assertThat(doneV2.status()).isEqualTo(SearchIndexTaskStatus.DONE);
        assertThat(doneV2.esAppliedRevision()).isEqualTo(2L);
        assertThat(doneV2.cacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("3");

        visibilityTable.put("9201", new VisibilityDecision("9201", "2", "PUBLIC", "READABLE"));
        PageResult searched = publicSearchService.search("RefreshLag", 1, 5, "relevance", "req-refresh-v2");
        assertThat(searched.rows()).hasSize(1);
        assertThat(searched.rows().get(0).title()).isEqualTo("RefreshLag Article V2");
    }

    private static String publicArticleEventJson(
            String eventId,
            String eventType,
            int schemaVersion,
            long articleId,
            long sourceRevision,
            String title,
            String summary,
            String content,
            long viewCount,
            long counterRevision) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "schemaVersion": %d,
                  "aggregateType": "ARTICLE",
                  "aggregateId": "%d",
                  "sourceRevision": "%d",
                  "publicationFactId": "pub-%d",
                  "occurredAt": "2026-11-26T08:00:00Z",
                  "producer": "knode-content",
                  "visibility": "PUBLIC",
                  "payload": {
                    "articleId": "%d",
                    "authorId": "50",
                    "title": "%s",
                    "summary": "%s",
                    "content": "%s",
                    "thumbnail": null,
                    "isTop": "0",
                    "isComment": "1",
                    "category": {"id": "10", "name": "Tech"},
                    "tags": [{"id": "1", "name": "Spring"}],
                    "createTime": "2026-11-20T01:02:03Z",
                    "publishedAt": "2026-11-26T08:00:00Z",
                    "viewCount": "%d",
                    "counterRevision": "%d"
                  }
                }
                """.formatted(
                eventId, eventType, schemaVersion, articleId, sourceRevision, articleId,
                articleId, title, summary, content, viewCount, counterRevision);
    }

    private static boolean hasVersionParam(String query, String expected) {
        if (query == null) {
            return false;
        }
        for (String pair : query.split("&")) {
            if (("version=" + expected).equals(pair)) {
                return true;
            }
        }
        return false;
    }

    private static <T> ObjectProvider<T> singleObjectProvider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
