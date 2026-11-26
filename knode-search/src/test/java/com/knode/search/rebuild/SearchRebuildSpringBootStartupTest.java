package com.knode.search.rebuild;

import com.knode.search.KnodeSearchApplication;
import com.knode.search.client.ServiceTokenClient;
import com.knode.search.model.QueryBudget;
import com.knode.search.rebuild.client.ContentSnapshotClient;
import com.knode.search.rebuild.client.HttpContentSnapshotClient;
import com.knode.search.rebuild.es.ElasticsearchIndexAdminClient;
import com.knode.search.rebuild.es.SearchIndexAdminClient;
import com.knode.search.rebuild.persistence.RebuildJobRecord;
import com.knode.search.rebuild.persistence.RebuildPhase;
import com.knode.search.rebuild.persistence.SearchRebuildRepository;
import com.knode.search.rebuild.service.SearchRebuildService;
import com.knode.search.support.LoopbackContentSnapshotStub;
import com.knode.search.support.LoopbackRedisStub;
import com.knode.search.support.StatefulElasticsearchStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {KnodeSearchApplication.class, SearchRebuildSpringBootStartupTest.TestTokenConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SearchRebuildSpringBootStartupTest {
    private static final String ENV = "it-boot-rebuild";
    private static final String ES_ALIAS = "knode_it_boot_rebuild_articles_search";
    private static final String DB_NAME = "search_rb_boot_" + UUID.randomUUID().toString().replace("-", "");

    private static LoopbackRedisStub cacheRedis;
    private static StatefulElasticsearchStub esStub;
    private static LoopbackContentSnapshotStub snapshotStub;

    @Autowired
    @Qualifier("searchConsumerDataSource")
    private DataSource searchConsumerDataSource;

    @Autowired
    private ContentSnapshotClient contentSnapshotClient;

    @Autowired
    private SearchIndexAdminClient searchIndexAdminClient;

    @Autowired
    private SearchRebuildRepository searchRebuildRepository;

    @Autowired
    private SearchRebuildService searchRebuildService;

    @BeforeAll
    static void startStubs() throws Exception {
        cacheRedis = new LoopbackRedisStub();
        esStub = new StatefulElasticsearchStub();
        esStub.setAlias(ES_ALIAS, ES_ALIAS + "_g1");
        snapshotStub = new LoopbackContentSnapshotStub();
        snapshotStub.setExpectedBearerToken("boot-rebuild-token");
        snapshotStub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(501L, 2L, "Boot Rebuild Article",
                        "Summary", "Body content", 1L, "Tech", List.of("Spring"), 12L, 1L, "2026-10-10T00:00:00Z")
        ));
    }

    @AfterAll
    static void stopStubs() throws Exception {
        if (snapshotStub != null) {
            snapshotStub.close();
        }
        if (esStub != null) {
            esStub.close();
        }
        if (cacheRedis != null) {
            cacheRedis.close();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("knode.environment", () -> ENV);
        registry.add("knode.search.elasticsearch.url", () -> esStub.baseUrl());
        registry.add("knode.search.elasticsearch.index", () -> ES_ALIAS);
        registry.add("knode.search.clients.content-origin", () -> snapshotStub.baseUrl());
        registry.add("knode.search.clients.content-snapshot-timeout-ms", () -> "5000");

        registry.add("knode.search.cache.enabled", () -> "true");
        registry.add("knode.search.cache.redis.host", () -> "127.0.0.1");
        registry.add("knode.search.cache.redis.port", () -> Integer.toString(cacheRedis.port()));

        registry.add("knode.search.consumer.enabled", () -> "true");
        registry.add("knode.search.consumer.datasource.url", () ->
                "jdbc:h2:mem:" + DB_NAME
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("knode.search.consumer.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("knode.search.consumer.datasource.username", () -> "sa");
        registry.add("knode.search.consumer.datasource.password", () -> "");

        registry.add("knode.search.consumer.stream.enabled", () -> "false");
        registry.add("knode.search.consumer.indexer.enabled", () -> "true");
        registry.add("knode.search.consumer.indexer.worker-enabled", () -> "false");
        registry.add("knode.search.rebuild.enabled", () -> "true");
        registry.add("knode.search.rebuild.worker-enabled", () -> "false");
        registry.add("knode.search.rebuild.instance-id", () -> "boot-rebuild-inst-1");
    }

    @Test
    void wiresRebuildPipelineAndCompletesEndToEndCutoverUnderRealSpringContext() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/search-consumer-schema.sql"));
        populator.execute(searchConsumerDataSource);

        assertThat(contentSnapshotClient).isInstanceOf(HttpContentSnapshotClient.class);
        assertThat(searchIndexAdminClient).isInstanceOf(ElasticsearchIndexAdminClient.class);
        assertThat(searchRebuildRepository).isNotNull();
        assertThat(searchRebuildService).isNotNull();

        RebuildJobRecord started = searchRebuildService.startRebuild("boot-req-1");
        RebuildJobRecord finished = searchRebuildService.runUntilTerminalOrLimit(started.jobId(), 20);

        assertThat(finished.phase()).isEqualTo(RebuildPhase.COMPLETED);
        assertThat(esStub.aliasTargets(ES_ALIAS)).containsExactly(ES_ALIAS + "_g2");
        assertThat(esStub.getRefreshedDocument(ES_ALIAS + "_g2", "501")).isNotNull();
        assertThat(esStub.indexExists(ES_ALIAS + "_g1")).isTrue();
    }

    @TestConfiguration
    static class TestTokenConfig {
        @Bean
        @Primary
        ServiceTokenClient testServiceTokenClient() {
            return new ServiceTokenClient() {
                @Override
                public String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
                    return "boot-rebuild-token";
                }

                @Override
                public void invalidate(String audience, List<String> permissions) {
                }
            };
        }
    }
}
