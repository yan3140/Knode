package com.knode.search.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.KnodeSearchApplication;
import com.knode.search.cache.SearchCacheInvalidator;
import com.knode.search.consumer.indexer.ArticleIndexWriter;
import com.knode.search.consumer.indexer.ElasticsearchArticleIndexWriter;
import com.knode.search.consumer.indexer.SearchIndexTaskWorker;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.consumer.stream.ContentStreamClient;
import com.knode.search.consumer.stream.ContentStreamConsumer;
import com.knode.search.consumer.stream.LettuceContentStreamClient;
import com.knode.search.support.LoopbackRedisStub;
import com.knode.search.support.StatefulElasticsearchStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = KnodeSearchApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SearchConsumerSpringBootStartupTest {
    private static final String ENV = "it-boot-consumer";
    private static final String STREAM_KEY = "knode:" + ENV + ":v1:events:content";
    private static final String GROUP = "knode-search-v1";
    private static final String CONSUMER_NAME = "knode-search-boot-test-1";
    private static final String ES_INDEX = "knode_it_boot_consumer_articles_search";
    private static final String DB_NAME = "search_boot_" + UUID.randomUUID().toString().replace("-", "");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LoopbackRedisStub streamRedis;
    private static LoopbackRedisStub cacheRedis;
    private static StatefulElasticsearchStub esStub;

    @Autowired
    @Qualifier("searchConsumerDataSource")
    private DataSource searchConsumerDataSource;

    @Autowired
    private ContentStreamClient contentStreamClient;

    @Autowired
    private ContentStreamConsumer contentStreamConsumer;

    @Autowired
    private ArticleIndexWriter articleIndexWriter;

    @Autowired
    private SearchIndexTaskWorker searchIndexTaskWorker;

    @Autowired
    private SearchConsumerRepository searchConsumerRepository;

    @Autowired
    private SearchCacheInvalidator searchCacheInvalidator;

    @BeforeAll
    static void startStubs() throws Exception {
        streamRedis = new LoopbackRedisStub();
        streamRedis.createGroup(STREAM_KEY, GROUP);
        cacheRedis = new LoopbackRedisStub();
        esStub = new StatefulElasticsearchStub();
    }

    @AfterAll
    static void stopStubs() throws Exception {
        if (esStub != null) {
            esStub.close();
        }
        if (cacheRedis != null) {
            cacheRedis.close();
        }
        if (streamRedis != null) {
            streamRedis.close();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("knode.environment", () -> ENV);
        registry.add("knode.search.elasticsearch.url", () -> esStub.baseUrl());
        registry.add("knode.search.elasticsearch.index", () -> ES_INDEX);
        registry.add("knode.search.elasticsearch.connect-timeout-ms", () -> "400");
        registry.add("knode.search.elasticsearch.connection-request-timeout-ms", () -> "400");
        registry.add("knode.search.elasticsearch.response-timeout-ms", () -> "2000");

        registry.add("knode.search.cache.enabled", () -> "true");
        registry.add("knode.search.cache.redis.host", () -> "127.0.0.1");
        registry.add("knode.search.cache.redis.port", () -> Integer.toString(cacheRedis.port()));
        registry.add("knode.search.cache.redis.connect-timeout-ms", () -> "400");
        registry.add("knode.search.cache.redis.command-timeout-ms", () -> "400");

        registry.add("knode.search.consumer.enabled", () -> "true");
        registry.add("knode.search.consumer.datasource.url", () ->
                "jdbc:h2:mem:" + DB_NAME
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("knode.search.consumer.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("knode.search.consumer.datasource.username", () -> "sa");
        registry.add("knode.search.consumer.datasource.password", () -> "");

        registry.add("knode.search.consumer.stream.enabled", () -> "true");
        registry.add("knode.search.consumer.stream.worker-enabled", () -> "false");
        registry.add("knode.search.consumer.stream.group", () -> GROUP);
        registry.add("knode.search.consumer.stream.consumer-name", () -> CONSUMER_NAME);
        registry.add("knode.search.consumer.stream.batch-size", () -> "5");
        registry.add("knode.search.consumer.stream.block-timeout-ms", () -> "50");
        registry.add("knode.search.consumer.stream.min-idle-ms", () -> "1000");
        registry.add("knode.search.consumer.stream.redis.host", () -> "127.0.0.1");
        registry.add("knode.search.consumer.stream.redis.port", () -> Integer.toString(streamRedis.port()));
        registry.add("knode.search.consumer.stream.redis.connect-timeout-ms", () -> "400");
        registry.add("knode.search.consumer.stream.redis.command-timeout-ms", () -> "800");

        registry.add("knode.search.consumer.indexer.enabled", () -> "true");
        registry.add("knode.search.consumer.indexer.worker-enabled", () -> "false");
        registry.add("knode.search.consumer.indexer.refresh-policy", () -> "wait_for");
    }

    @Test
    void bootsWithStreamAndIndexerEnabledWiresRealBeansAndProcessesSyntheticEvent() throws Exception {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/search-consumer-schema.sql"));
        populator.execute(searchConsumerDataSource);

        assertThat(contentStreamClient).isInstanceOf(LettuceContentStreamClient.class);
        assertThat(contentStreamClient.streamKey()).isEqualTo(STREAM_KEY);
        assertThat(contentStreamClient.groupName()).isEqualTo(GROUP);
        assertThat(contentStreamClient.consumerName()).isEqualTo(CONSUMER_NAME);
        assertThat(articleIndexWriter).isInstanceOf(ElasticsearchArticleIndexWriter.class);
        assertThat(contentStreamConsumer).isNotNull();
        assertThat(searchIndexTaskWorker).isNotNull();

        String syntheticEvent = """
                {
                  "eventId": "evt-boot-1001",
                  "eventType": "ArticlePublished",
                  "schemaVersion": 2,
                  "aggregateType": "ARTICLE",
                  "aggregateId": "1001",
                  "sourceRevision": "1",
                  "publicationFactId": "pub-1001",
                  "occurredAt": "2026-11-26T08:00:00Z",
                  "producer": "knode-content",
                  "visibility": "PUBLIC",
                  "payload": {
                    "articleId": "1001",
                    "authorId": "50",
                    "title": "Spring Boot Wired Consumer",
                    "summary": "Boot wiring regression",
                    "content": "Verified through real KnodeSearchApplication context",
                    "thumbnail": null,
                    "isTop": "0",
                    "isComment": "1",
                    "category": {"id": "10", "name": "Tech"},
                    "tags": [{"id": "1", "name": "Spring"}],
                    "createTime": "2026-11-20T01:02:03Z",
                    "publishedAt": "2026-11-26T08:00:00Z",
                    "viewCount": "12",
                    "counterRevision": "1"
                  }
                }
                """;
        streamRedis.addStreamMessage(STREAM_KEY, Map.of("event", syntheticEvent));

        ContentStreamConsumer.PollSummary pollSummary = contentStreamConsumer.pollOnce();
        assertThat(pollSummary.freshCount()).isEqualTo(1);
        assertThat(pollSummary.ackedCount()).isEqualTo(1);
        assertThat(streamRedis.pendingCount(STREAM_KEY, GROUP)).isZero();
        assertThat(searchConsumerRepository.findInboxStatus("evt-boot-1001")).contains("APPLIED");

        ArticleProjectionRecord projection = searchConsumerRepository.findProjection(1001L).orElseThrow();
        assertThat(projection.projectionRevision()).isEqualTo(1L);
        assertThat(projection.title()).isEqualTo("Spring Boot Wired Consumer");

        SearchIndexTaskWorker.WorkerCycleSummary workerSummary = searchIndexTaskWorker.processOnce();
        assertThat(workerSummary.appliedCount()).isEqualTo(1);

        SearchIndexTaskRecord task = searchConsumerRepository.findIndexTask(1001L).orElseThrow();
        assertThat(task.status()).isEqualTo(SearchIndexTaskStatus.DONE);
        assertThat(task.esAppliedRevision()).isEqualTo(1L);
        assertThat(task.cacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.DONE);
        assertThat(cacheRedis.getRaw(searchCacheInvalidator.epochKey())).isEqualTo("1");

        StatefulElasticsearchStub.StoredDoc refreshed = esStub.getRefreshedDocument(ES_INDEX, "1001");
        assertThat(refreshed).isNotNull();
        assertThat(refreshed.version()).isEqualTo(1L);
        JsonNode source = MAPPER.readTree(refreshed.sourceJson());
        assertThat(source.path("title").asText()).isEqualTo("Spring Boot Wired Consumer");
        assertThat(source.path("searchable").asBoolean()).isTrue();
    }
}
