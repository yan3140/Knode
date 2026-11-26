package com.knode.search.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.event.ContentEventParser;
import com.knode.search.consumer.indexer.ElasticsearchArticleIndexWriter;
import com.knode.search.consumer.indexer.IndexWriteResult;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.ContentEventIngestionService;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexOperation;
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
import java.util.List;
import java.util.Locale;
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

import static org.assertj.core.api.Assertions.assertThat;

class SearchConsumerAndIndexerIntegrationTest {
    private static final String ENV = "it-consumer";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String GROUP = "knode-search-v1";
    private static final String OTHER_GROUP = "knode-community-v1";
    private static final String ES_INDEX = "knode_it_consumer_articles_search";
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

    private SearchConsumerProperties consumerProps;
    private SearchConsumerRepository repository;
    private ContentEventIngestionService ingestionService;
    private LettuceContentStreamClient streamClientA;
    private LettuceContentStreamClient streamClientB;
    private ContentStreamConsumer consumerA;
    private ContentStreamConsumer consumerB;
    private ElasticsearchArticleIndexWriter indexWriter;
    private SearchCacheInvalidator cacheInvalidator;
    private SearchIndexTaskWorker indexWorker;
    private ArticleSearchCache articleSearchCache;
    private PublicArticleSearchService publicSearchService;
    private Map<String, VisibilityDecision> visibilityTable;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "search_it_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");
        hc.setMaximumPoolSize(6);
        dataSource = new HikariDataSource(hc);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/search-consumer-schema.sql"));
        populator.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        txTemplate = new TransactionTemplate(txManager);
        txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        txTemplate.setTimeout(5);

        clock = new MutableClock(Instant.parse("2026-11-26T08:00:00Z"));
        streamRedis = new LoopbackRedisStub();
        streamRedis.createGroup(STREAM_KEY, GROUP);
        streamRedis.createGroup(STREAM_KEY, OTHER_GROUP);

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
        cacheRedisTemplate = runtimeConfig.searchCacheRedisTemplate(cacheRedisFactory);

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
        consumerProps.getStream().setBatchSize(10);
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
        consumerA = new ContentStreamConsumer(streamClientA, ingestionService, consumerProps);
        consumerB = new ContentStreamConsumer(streamClientB, ingestionService, consumerProps);

        ObjectProvider<StringRedisTemplate> cacheProvider = singleObjectProvider(cacheRedisTemplate);
        cacheInvalidator = new SearchCacheInvalidator(cacheProvider, ENV);
        indexWriter = new ElasticsearchArticleIndexWriter(esClient, esProps, consumerProps, MAPPER);
        indexWorker = new SearchIndexTaskWorker(
                repository, txTemplate, indexWriter, cacheInvalidator, consumerProps, clock);

        SearchMetrics metrics = new SearchMetrics(new SimpleMeterRegistry());
        articleSearchCache = new ArticleSearchCache(cacheProvider, MAPPER, cacheProps, metrics, ENV);
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
    void deduplicatesByEventIdReclaimsAcrossInstancesAndUsesLeastPrivilegeStreamCommands() {
        String eventJson = publicArticleEventJson(
                "evt-dup-1", "ArticlePublished", 2, 101L, 1L,
                "Spring Search Dedup", "Summary", "Body text", 10L, 1L, "0", true);
        // Same eventId appended twice as distinct Stream records (at-least-once duplicate delivery).
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", eventJson));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", eventJson));

        // Another message delivered to instance A, which crashes BEFORE processing/ACK.
        String event2Json = publicArticleEventJson(
                "evt-reclaim-2", "ArticlePublished", 2, 102L, 1L,
                "Reclaimed Article", "Summary 2", "Body 2", 5L, 1L, "0", true);
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", event2Json));

        // Instance A polls only the first two records by manually reading 1 record unacked first?
        // Better: let consumerA process first 2 records (batch=2), then streamClientA read 3rd without ACK.
        consumerProps.getStream().setBatchSize(2);
        ContentStreamConsumer.PollSummary firstPoll = consumerA.pollOnce();
        assertThat(firstPoll.freshCount()).isEqualTo(2);
        assertThat(firstPoll.ackedCount()).isEqualTo(2);
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isZero();

        // Projection revision for 101 must be 1 (NOT 2) because duplicate eventId was skipped.
        ArticleProjectionRecord p101 = repository.findProjection(101L).orElseThrow();
        assertThat(p101.projectionRevision()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_event_inbox WHERE event_id = 'evt-dup-1'", Integer.class)).isEqualTo(1);

        // Instance A reads record 3 (evt-reclaim-2) from Stream and simulates crash before DB commit & ACK.
        List<StreamRecord> stolen = streamClientA.readNew(1, 50);
        assertThat(stolen).hasSize(1);
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isEqualTo(1);
        assertThat(repository.findProjection(102L)).isEmpty();

        // Before minIdleMs elapses, Instance B cannot reclaim it.
        ContentStreamConsumer.PollSummary beforeIdle = consumerB.pollOnce();
        assertThat(beforeIdle.reclaimedCount()).isZero();
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isEqualTo(1);

        // Age pending entry past minIdleMs -> Instance B reclaims, commits SQL, and XACKs.
        streamRedis.agePendingEntries(STREAM_KEY, GROUP, 2000L);
        ContentStreamConsumer.PollSummary afterIdle = consumerB.pollOnce();
        assertThat(afterIdle.reclaimedCount()).isEqualTo(1);
        assertThat(afterIdle.ackedCount()).isEqualTo(1);
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isZero();
        assertThat(repository.findProjection(102L)).isPresent();

        // Stream entries are NEVER trimmed/deleted; other consumer group state untouched; least-privilege commands only.
        assertThat(streamRedis.streamLength(STREAM_KEY)).isEqualTo(3);
        Set<String> forbiddenOps = Set.of("XGROUP", "XTRIM", "XDEL", "KEYS", "SCAN", "FLUSHDB", "FLUSHALL");
        for (List<String> cmd : streamRedis.recordedCommands()) {
            assertThat(forbiddenOps).doesNotContain(cmd.get(0).toUpperCase(Locale.ROOT));
        }
    }

    @Test
    void avoidsPrematureAckOnSqlRollbackAndDeduplicatesOnReclaimAfterAckFailure() {
        String eventRollback = publicArticleEventJson(
                "evt-rb-1", "ArticlePublished", 2, 201L, 1L,
                "Rollback Safe", "Sum", "Body", 1L, 1L, "0", true);
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", eventRollback));

        // 1) Simulate SQL failure inside ingestion -> rollback -> ZERO XACK.
        AtomicBoolean failDb = new AtomicBoolean(true);
        ContentEventIngestionService flakyIngestion = new ContentEventIngestionService(
                new ContentEventParser(), repository, txTemplate, consumerProps, MAPPER, clock) {
            @Override
            public IngestionOutcome ingestStreamMessage(String streamRecordId, Map<String, String> streamFields) {
                return txTemplate.execute(status -> {
                    IngestionOutcome out = super.ingestStreamMessage(streamRecordId, streamFields);
                    if (failDb.get()) {
                        throw new RuntimeException("Simulated SQL failure before commit");
                    }
                    return out;
                });
            }
        };
        ContentStreamConsumer flakyConsumer = new ContentStreamConsumer(streamClientA, flakyIngestion, consumerProps);
        ContentStreamConsumer.PollSummary rolledBack = flakyConsumer.pollOnce();
        assertThat(rolledBack.dbFailedCount()).isEqualTo(1);
        assertThat(rolledBack.ackedCount()).isZero();
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isEqualTo(1);
        assertThat(repository.findInboxStatus("evt-rb-1")).isEmpty();
        assertThat(repository.findProjection(201L)).isEmpty();
        assertThat(streamRedis.recordedCommands().stream()
                .filter(c -> "XACK".equalsIgnoreCase(c.get(0)))
                .count()).isZero();

        // 2) Clear DB failure, inject 1-shot XACK failure -> SQL commits, ACK fails -> reclaim deduplicates & ACKs!
        failDb.set(false);
        streamRedis.agePendingEntries(STREAM_KEY, GROUP, 2000L);
        streamRedis.failCommandTimes("XACK", "ERR simulated ack network drop", 1);

        ContentStreamConsumer.PollSummary commitThenAckFail = consumerA.pollOnce();
        assertThat(commitThenAckFail.reclaimedCount()).isEqualTo(1);
        assertThat(commitThenAckFail.ackFailedCount()).isEqualTo(1);
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isEqualTo(1);
        assertThat(repository.findInboxStatus("evt-rb-1")).contains("APPLIED");
        assertThat(repository.findProjection(201L).orElseThrow().projectionRevision()).isEqualTo(1L);

        // Instance B reclaims after idle -> Inbox dedup skips projection bump -> XACK succeeds!
        streamRedis.agePendingEntries(STREAM_KEY, GROUP, 2000L);
        ContentStreamConsumer.PollSummary reclaimedAndAcked = consumerB.pollOnce();
        assertThat(reclaimedAndAcked.reclaimedCount()).isEqualTo(1);
        assertThat(reclaimedAndAcked.ackedCount()).isEqualTo(1);
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isZero();
        assertThat(repository.findProjection(201L).orElseThrow().projectionRevision()).isEqualTo(1L);
    }

    @Test
    void recordsMalformedProtocolMessagesIgnoresNonSearchEventsAndRecoversTaskAfterAckCrash() throws Exception {
        // 1) Broken JSON with no eventId
        String badRecord1 = streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", "{not-valid-json"));
        // 2) Hidden event carrying forbidden body with valid eventId
        String badHiddenJson = """
                {
                  "eventId": "evt-bad-hidden-9",
                  "eventType": "ArticleDeleted",
                  "schemaVersion": 2,
                  "aggregateType": "ARTICLE",
                  "aggregateId": "301",
                  "sourceRevision": "8",
                  "occurredAt": "2026-11-26T08:00:00Z",
                  "producer": "knode-content",
                  "visibility": "DELETED",
                  "payload": {"articleId": "301", "visibility": "DELETED", "sourceRevision": "8", "content": "FORBIDDEN"}
                }
                """;
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", badHiddenJson));
        // 3) Valid non-search CommentCreated event
        String commentJson = """
                {
                  "eventId": "evt-comment-301",
                  "eventType": "CommentCreated",
                  "schemaVersion": 1,
                  "aggregateType": "COMMENT",
                  "aggregateId": "8801",
                  "sourceRevision": "1",
                  "occurredAt": "2026-11-26T08:01:00Z",
                  "producer": "knode-content",
                  "payload": {
                    "commentId": "8801",
                    "articleId": "301",
                    "rootCommentId": "-1",
                    "toCommentId": "-1",
                    "actorId": "50",
                    "receiverId": "50",
                    "commentType": "0",
                    "contentPreview": "Great post"
                  }
                }
                """;
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", commentJson));
        // 4) Valid public article event
        String goodArticle = publicArticleEventJson(
                "evt-good-302", "ArticlePublished", 2, 302L, 1L,
                "Survives Restart", "Durable task", "Body content", 7L, 1L, "0", true);
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", goodArticle));

        ContentStreamConsumer.PollSummary summary = consumerA.pollOnce();
        assertThat(summary.freshCount()).isEqualTo(4);
        assertThat(summary.protocolFailedCount()).isEqualTo(2);
        assertThat(summary.ackedCount()).isEqualTo(4);
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isZero();

        // Check durable failure records (stream-keyed & eventId-keyed)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_code FROM search_event_failure WHERE failure_key = ?",
                String.class, "stream:" + badRecord1)).isEqualTo("INVALID_JSON");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_code FROM search_event_failure WHERE failure_key = ?",
                String.class, "event:evt-bad-hidden-9")).isEqualTo("FORBIDDEN_BODY_ON_HIDDEN_EVENT");

        // Comment event recorded as IGNORED, did not pollute projection or index task
        assertThat(repository.findInboxStatus("evt-comment-301")).contains("IGNORED");
        assertThat(repository.findProjection(301L)).isEmpty();
        assertThat(repository.findIndexTask(301L)).isEmpty();

        // Simulate process restart AFTER XACK: stream PEL is 0, ES has nothing yet; fresh worker recovers from DB!
        assertThat(esStub.getDocument(ES_INDEX, "302")).isNull();
        SearchIndexTaskWorker restartedWorker = new SearchIndexTaskWorker(
                repository, txTemplate, indexWriter, cacheInvalidator, consumerProps, clock);
        SearchIndexTaskWorker.WorkerCycleSummary cycle = restartedWorker.processOnce();
        assertThat(cycle.appliedCount()).isEqualTo(1);
        StatefulElasticsearchStub.StoredDoc doc = esStub.getDocument(ES_INDEX, "302");
        assertThat(doc).isNotNull();
        assertThat(doc.version()).isEqualTo(1L);
        assertThat(MAPPER.readTree(doc.sourceJson()).path("title").asText()).isEqualTo("Survives Restart");
    }

    @Test
    void enforcesDualVersionProjectionOutOfOrderRulesAndDeleteBarrier() throws Exception {
        // 1) Counter arrives BEFORE any content for article 401 -> stored, NO index task, NO ES doc!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event",
                counterEventJson("evt-401-c5", 401L, 50L, 5L, "PUBLIC")));
        consumerA.pollOnce();
        indexWorker.processOnce();

        ArticleProjectionRecord pBefore = repository.findProjection(401L).orElseThrow();
        assertThat(pBefore.hasContent()).isFalse();
        assertThat(pBefore.counterRevision()).isEqualTo(5L);
        assertThat(pBefore.viewCount()).isEqualTo(50L);
        assertThat(pBefore.projectionRevision()).isZero();
        assertThat(repository.findIndexTask(401L)).isEmpty();
        assertThat(esStub.getDocument(ES_INDEX, "401")).isNull();

        // 2) ArticlePublished v1 arrives with OLDER counterRevision=2, viewCount=10 -> merges & preserves viewCount=50!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event",
                publicArticleEventJson("evt-401-v1", "ArticlePublished", 1, 401L, 1L,
                        "Initial Title", "Sum", "Body v1", 10L, 2L, null, true)));
        consumerA.pollOnce();
        indexWorker.processOnce();

        ArticleProjectionRecord pMerged = repository.findProjection(401L).orElseThrow();
        assertThat(pMerged.contentRevision()).isEqualTo(1L);
        assertThat(pMerged.counterRevision()).isEqualTo(5L);
        assertThat(pMerged.viewCount()).isEqualTo(50L);
        assertThat(pMerged.projectionRevision()).isEqualTo(1L);
        JsonNode esV1 = MAPPER.readTree(esStub.getDocument(ES_INDEX, "401").sourceJson());
        assertThat(esV1.path("sourceRevision").asText()).isEqualTo("1");
        assertThat(esV1.path("viewCount").asLong()).isEqualTo(50L);
        assertThat(esV1.path("searchable").asBoolean()).isTrue();

        // 3) Newer counterRevision=8 arrives -> updates viewCount=88, keeps contentRevision=1!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event",
                counterEventJson("evt-401-c8", 401L, 88L, 8L, "PUBLIC")));
        consumerA.pollOnce();
        indexWorker.processOnce();
        JsonNode esAfterCounter = MAPPER.readTree(esStub.getDocument(ES_INDEX, "401").sourceJson());
        assertThat(esAfterCounter.path("sourceRevision").asText()).isEqualTo("1");
        assertThat(esAfterCounter.path("viewCount").asLong()).isEqualTo(88L);
        assertThat(esStub.getDocument(ES_INDEX, "401").version()).isEqualTo(2L);

        // 4) Legacy v2 ArticleContentChanged (null publishedAt/publicationFactId, sourceRevision=2, older counterRevision=6)
        //    must update title to v2 AND keep newer viewCount=88!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event",
                publicArticleEventJson("evt-401-v2", "ArticleContentChanged", 2, 401L, 2L,
                        "Updated Legacy v2 Title", "Sum2", "Body v2", 60L, 6L, "1", false)));
        consumerA.pollOnce();
        indexWorker.processOnce();
        JsonNode esV2 = MAPPER.readTree(esStub.getDocument(ES_INDEX, "401").sourceJson());
        assertThat(esV2.path("sourceRevision").asText()).isEqualTo("2");
        assertThat(esV2.path("title").asText()).isEqualTo("Updated Legacy v2 Title");
        assertThat(esV2.path("viewCount").asLong()).isEqualTo(88L);

        // 5) ArticleDeleted at sourceRevision=8 -> persistent tombstone with searchable=false & ZERO text fields!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event",
                hiddenArticleEventJson("evt-401-del8", "ArticleDeleted", 2, 401L, 8L, "DELETED")));
        consumerA.pollOnce();
        indexWorker.processOnce();

        StatefulElasticsearchStub.StoredDoc tombDoc = esStub.getDocument(ES_INDEX, "401");
        assertThat(tombDoc.version()).isEqualTo(4L);
        JsonNode tombJson = MAPPER.readTree(tombDoc.sourceJson());
        assertThat(tombJson.path("searchable").asBoolean()).isFalse();
        assertThat(tombJson.path("sourceRevision").asText()).isEqualTo("8");
        assertThat(tombJson.has("title")).isFalse();
        assertThat(tombJson.has("summary")).isFalse();
        assertThat(tombJson.has("content")).isFalse();
        assertThat(tombJson.has("tagNames")).isFalse();

        // 6) Late v7 public event + late v7 withdraw + newer counterRevision=9 MUST NOT resurrect article 401!
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event",
                publicArticleEventJson("evt-401-late-v7", "ArticleContentChanged", 2, 401L, 7L,
                        "Late Ghost v7", "Ghost", "Ghost body", 70L, 7L, "0", true)));
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event",
                counterEventJson("evt-401-c9", 401L, 99L, 9L, "DELETED")));
        consumerA.pollOnce();
        indexWorker.processOnce();

        assertThat(repository.findInboxStatus("evt-401-late-v7")).contains("STALE_IGNORED");
        ArticleProjectionRecord pFinal = repository.findProjection(401L).orElseThrow();
        assertThat(pFinal.visibility()).isEqualTo(ArticleVisibility.DELETED);
        assertThat(pFinal.contentRevision()).isEqualTo(8L);
        assertThat(pFinal.projectionRevision()).isEqualTo(4L);
        // ES still holds the v4 tombstone, never resurrected.
        StatefulElasticsearchStub.StoredDoc finalEs = esStub.getDocument(ES_INDEX, "401");
        assertThat(finalEs.version()).isEqualTo(4L);
        assertThat(MAPPER.readTree(finalEs.sourceJson()).path("searchable").asBoolean()).isFalse();
    }

    @Test
    void fencesStaleWorkerConditionallyAndRejectsLateInFlightEsUpsertAndTombstoneWith409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // === Case A: In-flight old v1 UPSERT arrives at ES AFTER newer v2 TOMBSTONE ===
            ingestionService.ingestStreamMessage("s-1", Map.of("event",
                    publicArticleEventJson("evt-501-v1", "ArticlePublished", 2, 501L, 1L,
                            "Old Public v1", "Sum", "Body", 10L, 1L, "0", true)));
            List<SearchIndexTaskRecord> claimedV1 = indexWorker.claimBatch();
            assertThat(claimedV1).hasSize(1);
            SearchIndexTaskRecord taskV1 = claimedV1.get(0);
            assertThat(taskV1.targetProjectionRevision()).isEqualTo(1L);
            assertThat(taskV1.claimGeneration()).isEqualTo(1L);

            CountDownLatch oldUpsertReachedEs = new CountDownLatch(1);
            CountDownLatch releaseOldUpsert = new CountDownLatch(1);
            esStub.setInterceptor((req, delegate) -> {
                if ("PUT".equals(req.method()) && req.path().endsWith("/_doc/501")
                        && hasVersionParam(req.query(), "1")) {
                    oldUpsertReachedEs.countDown();
                    try {
                        releaseOldUpsert.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                return delegate.get();
            });

            // Start stale Worker-1 sending v1 UPSERT to ES (will block right before ES applies it).
            Future<SearchIndexTaskWorker.TaskProcessOutcome> staleWorkerFuture =
                    pool.submit(() -> indexWorker.executeClaimedTask(taskV1));
            assertThat(oldUpsertReachedEs.await(5, TimeUnit.SECONDS)).isTrue();

            // Meanwhile, ArticleDeleted v8 arrives -> projectionRevision=2 -> Worker-2 claims & writes v2 TOMBSTONE!
            ingestionService.ingestStreamMessage("s-2", Map.of("event",
                    hiddenArticleEventJson("evt-501-del8", "ArticleDeleted", 2, 501L, 8L, "DELETED")));
            SearchIndexTaskWorker.WorkerCycleSummary w2Summary = indexWorker.processOnce();
            assertThat(w2Summary.appliedCount()).isEqualTo(1);
            assertThat(esStub.getDocument(ES_INDEX, "501").version()).isEqualTo(2L);
            assertThat(MAPPER.readTree(esStub.getDocument(ES_INDEX, "501").sourceJson())
                    .path("searchable").asBoolean()).isFalse();

            // Release the delayed in-flight v1 UPSERT -> ES returns 409 -> Worker-1 sees superseded -> SQL rejects!
            releaseOldUpsert.countDown();
            SearchIndexTaskWorker.TaskProcessOutcome staleOutcome = staleWorkerFuture.get(5, TimeUnit.SECONDS);
            assertThat(staleOutcome).isEqualTo(SearchIndexTaskWorker.TaskProcessOutcome.STALE_REJECTED);

            // ES STILL holds v2 tombstone; SQL task STILL DONE at revision 2.
            StatefulElasticsearchStub.StoredDoc doc501 = esStub.getDocument(ES_INDEX, "501");
            assertThat(doc501.version()).isEqualTo(2L);
            assertThat(MAPPER.readTree(doc501.sourceJson()).path("searchable").asBoolean()).isFalse();
            SearchIndexTaskRecord sql501 = repository.findIndexTask(501L).orElseThrow();
            assertThat(sql501.status()).isEqualTo(SearchIndexTaskStatus.DONE);
            assertThat(sql501.targetProjectionRevision()).isEqualTo(2L);
            assertThat(sql501.operation()).isEqualTo(SearchIndexOperation.TOMBSTONE);

            // === Case B: In-flight old TOMBSTONE (version=1) arrives at ES AFTER newer republish UPSERT (version=2) ===
            esStub.clearInterceptor();
            ingestionService.ingestStreamMessage("s-3", Map.of("event",
                    hiddenArticleEventJson("evt-502-w1", "ArticleVisibilityChanged", 2, 502L, 1L, "WITHDRAWN")));
            SearchIndexTaskRecord staleTombstoneTask = indexWorker.claimBatch().get(0);
            assertThat(staleTombstoneTask.targetProjectionRevision()).isEqualTo(1L);

            CountDownLatch oldTombReachedEs = new CountDownLatch(1);
            CountDownLatch releaseOldTomb = new CountDownLatch(1);
            esStub.setInterceptor((req, delegate) -> {
                if ("PUT".equals(req.method()) && req.path().endsWith("/_doc/502")
                        && hasVersionParam(req.query(), "1")) {
                    oldTombReachedEs.countDown();
                    try {
                        releaseOldTomb.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                return delegate.get();
            });

            Future<SearchIndexTaskWorker.TaskProcessOutcome> staleTombFuture =
                    pool.submit(() -> indexWorker.executeClaimedTask(staleTombstoneTask));
            assertThat(oldTombReachedEs.await(5, TimeUnit.SECONDS)).isTrue();

            // Republish at sourceRevision=2 -> projectionRevision=2 -> Worker-2 writes searchable v2!
            ingestionService.ingestStreamMessage("s-4", Map.of("event",
                    publicArticleEventJson("evt-502-pub2", "ArticleVisibilityChanged", 2, 502L, 2L,
                            "Republished v2", "Fresh", "Fresh body", 12L, 1L, "0", true)));
            indexWorker.processOnce();
            assertThat(esStub.getDocument(ES_INDEX, "502").version()).isEqualTo(2L);

            // Release stale v1 tombstone -> rejected by ES 409; searchable v2 stays intact!
            releaseOldTomb.countDown();
            assertThat(staleTombFuture.get(5, TimeUnit.SECONDS))
                    .isEqualTo(SearchIndexTaskWorker.TaskProcessOutcome.STALE_REJECTED);
            StatefulElasticsearchStub.StoredDoc doc502 = esStub.getDocument(ES_INDEX, "502");
            assertThat(doc502.version()).isEqualTo(2L);
            assertThat(MAPPER.readTree(doc502.sourceJson()).path("searchable").asBoolean()).isTrue();
            assertThat(MAPPER.readTree(doc502.sourceJson()).path("title").asText()).isEqualTo("Republished v2");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recoversEsWriteSucceededButLocalSqlMarkFailedViaLeaseExpiryAndIdempotent409Verification() {
        ingestionService.ingestStreamMessage("s-601", Map.of("event",
                publicArticleEventJson("evt-601-v1", "ArticlePublished", 2, 601L, 1L,
                        "Idempotent 409 Recovery", "Sum", "Body", 5L, 1L, "0", true)));

        // Worker-1 claims task (gen=1) and writes directly to ES (succeeds!), then crashes BEFORE local SQL mark.
        SearchIndexTaskRecord worker1Claim = indexWorker.claimBatch().get(0);
        assertThat(worker1Claim.claimGeneration()).isEqualTo(1L);
        IndexWriteResult firstWrite = indexWriter.write(worker1Claim);
        assertThat(firstWrite.status()).isEqualTo(IndexWriteResult.Status.APPLIED);
        assertThat(esStub.getDocument(ES_INDEX, "601").version()).isEqualTo(1L);
        assertThat(repository.findIndexTask(601L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.LEASED);

        // Before lease expires, Worker-2 cannot claim it.
        assertThat(indexWorker.claimBatch()).isEmpty();

        // Advance clock past 30s lease -> Worker-2 reclaims (gen=2), retries same version=1 -> ES 409 ->
        // GET /_doc/601 verifies exact same version & payload -> IDEMPOTENT_APPLIED -> SQL marked DONE!
        clock.advance(Duration.ofSeconds(31));
        SearchIndexTaskWorker.WorkerCycleSummary recovery = indexWorker.processOnce();
        assertThat(recovery.claimedCount()).isEqualTo(1);
        assertThat(recovery.appliedCount()).isEqualTo(1);

        SearchIndexTaskRecord afterRecovery = repository.findIndexTask(601L).orElseThrow();
        assertThat(afterRecovery.status()).isEqualTo(SearchIndexTaskStatus.DONE);
        assertThat(afterRecovery.claimGeneration()).isEqualTo(2L);
        assertThat(afterRecovery.esAppliedRevision()).isEqualTo(1L);

        // Late Worker-1 wakes up and tries to mark completion using stale claimGeneration=1 -> conditionally rejected!
        SearchIndexTaskWorker.TaskProcessOutcome lateWorker1 =
                indexWorker.completeClaimedTask(worker1Claim, IndexWriteResult.applied());
        assertThat(lateWorker1).isEqualTo(SearchIndexTaskWorker.TaskProcessOutcome.STALE_REJECTED);
    }

    @Test
    void movesTaskToDeadAfterMaxAttemptsRetainingReasonAndRecoverablePayload() {
        ingestionService.ingestStreamMessage("s-701", Map.of("event",
                publicArticleEventJson("evt-701-v1", "ArticlePublished", 2, 701L, 1L,
                        "Retry Exhaustion", "Sum", "Recoverable Body", 3L, 1L, "0", true)));
        esStub.setInterceptor((req, delegate) -> StatefulElasticsearchStub.StubResponse.status(
                503, "{\"error\":\"service_unavailable\"}"));

        // maxAttempts = 3
        indexWorker.processOnce();
        SearchIndexTaskRecord after1 = repository.findIndexTask(701L).orElseThrow();
        assertThat(after1.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(after1.attempts()).isEqualTo(1);
        assertThat(after1.lastError()).contains("503");

        clock.advance(Duration.ofSeconds(2));
        indexWorker.processOnce();
        SearchIndexTaskRecord after2 = repository.findIndexTask(701L).orElseThrow();
        assertThat(after2.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(after2.attempts()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(5));
        indexWorker.processOnce();
        SearchIndexTaskRecord dead = repository.findIndexTask(701L).orElseThrow();
        assertThat(dead.status()).isEqualTo(SearchIndexTaskStatus.DEAD);
        assertThat(dead.attempts()).isEqualTo(3);
        assertThat(dead.lastError()).contains("503");
        assertThat(dead.payloadJson()).contains("Recoverable Body");
    }

    @Test
    void invalidatesCacheReliablyCompensatesRedisFailureAndBlocksConcurrentStaleQueryWriteback() throws Exception {
        // 1) Ingest & index article 801 v1, warm cache under epoch 0 (2 requests -> cached).
        ingestionService.ingestStreamMessage("s-801-1", Map.of("event",
                publicArticleEventJson("evt-801-v1", "ArticlePublished", 2, 801L, 1L,
                        "CacheEpoch Article Alpha", "VersionOne", "Alpha body", 10L, 1L, "0", true)));
        indexWorker.processOnce();
        visibilityTable.put("801", new VisibilityDecision("801", "1", "PUBLIC", "READABLE"));

        PageResult warm1 = publicSearchService.search("CacheEpoch", 1, 5, "relevance", "req-1");
        PageResult warm2 = publicSearchService.search("CacheEpoch", 1, 5, "relevance", "req-2");
        assertThat(warm1.rows()).hasSize(1);
        assertThat(warm2.rows().get(0).title()).isEqualTo("CacheEpoch Article Alpha");
        String epochBeforeUpdate = cacheRedis.getRaw(cacheInvalidator.epochKey());
        assertThat(epochBeforeUpdate).isEqualTo("1");
        assertThat(cacheRedis.snapshot().keySet())
                .anyMatch(k -> k.startsWith("knode:" + ENV + ":v1:search:result:") && k.endsWith(":e1"));

        // 2) Update article 801 to v2 while cache Redis INCR fails -> ES updated, durable cache compensation stays PENDING!
        cacheRedis.failCommand("INCR", "ERR cache redis temporarily unavailable");
        ingestionService.ingestStreamMessage("s-801-2", Map.of("event",
                publicArticleEventJson("evt-801-v2", "ArticleContentChanged", 2, 801L, 2L,
                        "CacheEpoch Article Beta", "VersionTwo", "Beta body", 11L, 1L, "0", true)));
        indexWorker.processOnce();

        SearchIndexTaskRecord pendingComp = repository.findIndexTask(801L).orElseThrow();
        assertThat(pendingComp.status()).isEqualTo(SearchIndexTaskStatus.DONE);
        assertThat(pendingComp.esAppliedRevision()).isEqualTo(2L);
        assertThat(pendingComp.cacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);
        assertThat(pendingComp.cacheLastError()).isNotBlank();

        // 3) Cache Redis recovers -> advance clock past compensation backoff -> worker advances epoch & marks DONE!
        cacheRedis.clearFaults();
        clock.advance(Duration.ofSeconds(2));
        SearchIndexTaskWorker.CacheCompensationSummary comp = indexWorker.processPendingCacheInvalidations();
        assertThat(comp.completedCount()).isEqualTo(1);
        assertThat(repository.findIndexTask(801L).orElseThrow().cacheInvalidationStatus())
                .isEqualTo(CacheInvalidationStatus.DONE);
        assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("2");

        // Fresh search sees v2 immediately (old :e1 entry bypassed).
        visibilityTable.put("801", new VisibilityDecision("801", "2", "PUBLIC", "READABLE"));
        PageResult freshV2 = publicSearchService.search("CacheEpoch", 1, 5, "relevance", "req-3");
        assertThat(freshV2.rows()).hasSize(1);
        assertThat(freshV2.rows().get(0).title()).isEqualTo("CacheEpoch Article Beta");

        // 4) Concurrent stale query protection:
        //    Query Q1 starts under epoch=2, misses :e2 (wait — clear cache keys except epoch=2 so Q1 hits ES),
        //    blocks inside ES stub while v3 is indexed and epoch advances to 3.
        cacheRedis.clearData();
        cacheRedis.putRaw(cacheInvalidator.epochKey(), "2");
        // Pre-warm admission counter so Q1 would write to cache if not blocked by epoch check!
        publicSearchService.search("CacheEpoch", 1, 5, "relevance", "req-admit-only");
        cacheRedis.snapshot().keySet().stream()
                .filter(k -> k.contains(":result:"))
                .forEach(k -> {});

        CountDownLatch slowQueryAtEs = new CountDownLatch(1);
        CountDownLatch releaseSlowQuery = new CountDownLatch(1);
        AtomicBoolean interceptedOnce = new AtomicBoolean(false);
        esStub.setInterceptor((req, delegate) -> {
            if ("POST".equals(req.method()) && req.path().endsWith("/_search")
                    && interceptedOnce.compareAndSet(false, true)) {
                StatefulElasticsearchStub.StubResponse v2Resp = delegate.get();
                slowQueryAtEs.countDown();
                try {
                    releaseSlowQuery.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return v2Resp;
            }
            return delegate.get();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PageResult> slowQueryFuture = pool.submit(() ->
                    publicSearchService.search("CacheEpoch", 1, 5, "relevance", "req-slow-q1"));
            assertThat(slowQueryAtEs.await(5, TimeUnit.SECONDS)).isTrue();

            // While Q1 is paused with v2 snapshot, ingest v3 & run indexWorker -> ES=v3 & Redis epoch advances to 3!
            ingestionService.ingestStreamMessage("s-801-3", Map.of("event",
                    publicArticleEventJson("evt-801-v3", "ArticleContentChanged", 2, 801L, 3L,
                            "CacheEpoch Article Gamma", "VersionThree", "Gamma body", 15L, 1L, "0", true)));
            indexWorker.processOnce();
            assertThat(cacheRedis.getRaw(cacheInvalidator.epochKey())).isEqualTo("3");

            // Release Q1 -> Q1 MUST NOT write stale v2 into current epoch :e3 (nor into :e2 after epoch check)!
            releaseSlowQuery.countDown();
            slowQueryFuture.get(5, TimeUnit.SECONDS);

            assertThat(cacheRedis.snapshot().keySet())
                    .noneMatch(k -> k.contains(":result:") && k.endsWith(":e3"));

            // Next query Q2 reads epoch=3 -> gets fresh v3 from ES!
            esStub.clearInterceptor();
            visibilityTable.put("801", new VisibilityDecision("801", "3", "PUBLIC", "READABLE"));
            PageResult q2 = publicSearchService.search("CacheEpoch", 1, 5, "relevance", "req-q2");
            assertThat(q2.rows()).hasSize(1);
            assertThat(q2.rows().get(0).title()).isEqualTo("CacheEpoch Article Gamma");
        } finally {
            pool.shutdownNow();
        }
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
            long counterRevision,
            String isTop,
            boolean includeFirstPubMeta) {
        String pubFactField = includeFirstPubMeta ? "\"pub-" + articleId + "\"" : "null";
        String publishedAtField = includeFirstPubMeta ? "\"2026-11-26T08:00:00Z\"" : "null";
        String isTopField = isTop == null ? "" : "\"isTop\":\"" + isTop + "\",";
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "schemaVersion": %d,
                  "aggregateType": "ARTICLE",
                  "aggregateId": "%d",
                  "sourceRevision": "%d",
                  "publicationFactId": %s,
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
                    %s
                    "isComment": "1",
                    "category": {"id": "10", "name": "Tech"},
                    "tags": [{"id": "1", "name": "Spring"}],
                    "createTime": "2026-11-20T01:02:03Z",
                    "publishedAt": %s,
                    "viewCount": "%d",
                    "counterRevision": "%d"
                  }
                }
                """.formatted(
                eventId, eventType, schemaVersion, articleId, sourceRevision, pubFactField,
                articleId, title, summary, content, isTopField, publishedAtField, viewCount, counterRevision);
    }

    private static String hiddenArticleEventJson(
            String eventId,
            String eventType,
            int schemaVersion,
            long articleId,
            long sourceRevision,
            String visibility) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "schemaVersion": %d,
                  "aggregateType": "ARTICLE",
                  "aggregateId": "%d",
                  "sourceRevision": "%d",
                  "publicationFactId": null,
                  "occurredAt": "2026-11-26T08:05:00Z",
                  "producer": "knode-content",
                  "visibility": "%s",
                  "payload": {
                    "articleId": "%d",
                    "visibility": "%s",
                    "sourceRevision": "%d"
                  }
                }
                """.formatted(
                eventId, eventType, schemaVersion, articleId, sourceRevision, visibility,
                articleId, visibility, sourceRevision);
    }

    private static String counterEventJson(
            String eventId,
            long articleId,
            long viewCount,
            long counterRevision,
            String visibility) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "ArticleCounterChanged",
                  "schemaVersion": 1,
                  "aggregateType": "ARTICLE_COUNTER",
                  "aggregateId": "%d",
                  "sourceRevision": "%d",
                  "publicationFactId": null,
                  "occurredAt": "2026-11-26T08:02:00Z",
                  "producer": "knode-content",
                  "visibility": "%s",
                  "payload": {
                    "articleId": "%d",
                    "viewCount": "%d",
                    "counterRevision": "%d"
                  }
                }
                """.formatted(eventId, articleId, counterRevision, visibility, articleId, viewCount, counterRevision);
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
