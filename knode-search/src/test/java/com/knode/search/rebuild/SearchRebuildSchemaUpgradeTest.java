package com.knode.search.rebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.ArticleVersionBarrier;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.rebuild.persistence.RebuildJobRecord;
import com.knode.search.rebuild.persistence.RebuildPhase;
import com.knode.search.rebuild.persistence.SearchRebuildRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRebuildSchemaUpgradeTest {

    @Test
    void upgradesSearch02SchemaToSearch03PreservingGeneration1DataAndBackfillingVersionBarriers() {
        String dbName = "search_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            // 1) Bootstrap legacy SEARCH-02 schema (before SEARCH-03 columns/tables existed).
            jdbc.execute("""
                    CREATE TABLE search_event_inbox (
                        event_id VARCHAR(128) NOT NULL,
                        event_type VARCHAR(64) NOT NULL,
                        aggregate_type VARCHAR(64) NOT NULL,
                        aggregate_id VARCHAR(64) NOT NULL,
                        source_revision BIGINT NOT NULL,
                        stream_record_id VARCHAR(64) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        processed_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (event_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbc.execute("""
                    CREATE TABLE search_event_failure (
                        failure_key VARCHAR(128) NOT NULL,
                        event_id VARCHAR(128) NULL,
                        stream_record_id VARCHAR(64) NOT NULL,
                        error_code VARCHAR(64) NOT NULL,
                        error_message VARCHAR(512) NOT NULL,
                        payload_sha256 CHAR(64) NOT NULL,
                        payload_bytes INT NOT NULL,
                        recorded_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (failure_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbc.execute("""
                    CREATE TABLE search_article_projection (
                        article_id BIGINT NOT NULL,
                        has_content TINYINT(1) NOT NULL DEFAULT 0,
                        content_revision BIGINT NOT NULL DEFAULT 0,
                        visibility VARCHAR(32) NOT NULL DEFAULT 'NONE',
                        counter_revision BIGINT NOT NULL DEFAULT 0,
                        view_count BIGINT NOT NULL DEFAULT 0,
                        projection_revision BIGINT NOT NULL DEFAULT 0,
                        title VARCHAR(512) NULL,
                        summary TEXT NULL,
                        content LONGTEXT NULL,
                        category_id BIGINT NULL,
                        category_name VARCHAR(255) NULL,
                        tag_names_json TEXT NULL,
                        thumbnail VARCHAR(1024) NULL,
                        is_top VARCHAR(16) NULL,
                        create_time_ms BIGINT NULL,
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (article_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbc.execute("""
                    CREATE TABLE search_index_task (
                        article_id BIGINT NOT NULL,
                        target_projection_revision BIGINT NOT NULL,
                        operation VARCHAR(32) NOT NULL,
                        payload_json LONGTEXT NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        attempts INT NOT NULL DEFAULT 0,
                        max_attempts INT NOT NULL DEFAULT 5,
                        due_at_ms BIGINT NOT NULL,
                        lease_until_ms BIGINT NULL,
                        claim_generation BIGINT NOT NULL DEFAULT 0,
                        last_error VARCHAR(512) NULL,
                        es_applied_revision BIGINT NOT NULL DEFAULT 0,
                        cache_invalidation_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
                        cache_invalidation_attempts INT NOT NULL DEFAULT 0,
                        cache_invalidation_due_at_ms BIGINT NULL,
                        cache_last_error VARCHAR(512) NULL,
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (article_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            // Seed existing SEARCH-02 rows (public + deleted articles).
            jdbc.update("""
                    INSERT INTO search_event_inbox (event_id, event_type, aggregate_type, aggregate_id, source_revision, stream_record_id, status, processed_at_ms)
                    VALUES ('evt-legacy-1', 'ArticlePublished', 'article', '1001', 5, '1-0', 'APPLIED', 1700000000000)
                    """);
            jdbc.update("""
                    INSERT INTO search_article_projection (article_id, has_content, content_revision, visibility, counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms)
                    VALUES (1001, 1, 5, 'PUBLIC', 2, 42, 3, 'Legacy Public', 'Sum', 'Body', 1700000000000)
                    """);
            jdbc.update("""
                    INSERT INTO search_article_projection (article_id, has_content, content_revision, visibility, counter_revision, view_count, projection_revision, updated_at_ms)
                    VALUES (1002, 1, 9, 'DELETED', 0, 0, 4, 1700000000100)
                    """);
            jdbc.update("""
                    INSERT INTO search_index_task (article_id, target_projection_revision, operation, payload_json, status, attempts, max_attempts, due_at_ms, claim_generation, es_applied_revision, cache_invalidation_status, cache_invalidation_attempts, updated_at_ms)
                    VALUES (1001, 3, 'UPSERT', '{"id":1001,"searchable":true}', 'DONE', 0, 5, 1700000000000, 1, 3, 'DONE', 0, 1700000000000)
                    """);

            // 2) Execute additive SEARCH-02 -> SEARCH-03 upgrade SQL.
            ResourceDatabasePopulator upgradePopulator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/search-rebuild-03-upgrade.sql"));
            upgradePopulator.execute(ds);

            // 3) Verify legacy rows are preserved under index_generation = 1 and version barriers are backfilled.
            ObjectMapper mapper = new ObjectMapper();
            SearchConsumerRepository consumerRepo = new SearchConsumerRepository(jdbc, mapper);
            SearchRebuildRepository rebuildRepo = new SearchRebuildRepository(jdbc, consumerRepo, mapper);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

            assertThat(consumerRepo.findInboxStatus("evt-legacy-1")).contains("APPLIED");

            ArticleProjectionRecord p1001 = consumerRepo.findProjection(1L, 1001L).orElseThrow();
            assertThat(p1001.contentRevision()).isEqualTo(5L);
            assertThat(p1001.visibility()).isEqualTo(ArticleVisibility.PUBLIC);
            assertThat(p1001.title()).isEqualTo("Legacy Public");

            ArticleVersionBarrier b1001 = consumerRepo.findVersionBarrier(1001L).orElseThrow();
            assertThat(b1001.maxContentRevision()).isEqualTo(5L);
            assertThat(b1001.lastVisibility()).isEqualTo(ArticleVisibility.PUBLIC);

            ArticleVersionBarrier b1002 = consumerRepo.findVersionBarrier(1002L).orElseThrow();
            assertThat(b1002.maxContentRevision()).isEqualTo(9L);
            assertThat(b1002.lastVisibility()).isEqualTo(ArticleVisibility.DELETED);

            SearchIndexTaskRecord t1001 = consumerRepo.findIndexTask(1L, 1001L).orElseThrow();
            assertThat(t1001.indexGeneration()).isEqualTo(1L);
            assertThat(t1001.targetProjectionRevision()).isEqualTo(3L);

            // 4) Verify new SEARCH-03 rebuild job & candidate generation-2 isolation work immediately on upgraded DB.
            RebuildJobRecord job = tx.execute(status -> rebuildRepo.startJobInCurrentTx(
                    "rb-upgrade-1", "knode_articles_search", "knode_articles_search_g1",
                    "req-upgrade-1", "inst-1", 60_000L, 1700000001000L));
            assertThat(job).isNotNull();
            assertThat(job.phase()).isEqualTo(RebuildPhase.INIT);
            assertThat(job.sourceGeneration()).isEqualTo(1L);
            assertThat(job.candidateGeneration()).isEqualTo(2L);
            assertThat(job.candidatePhysicalIndex()).isEqualTo("knode_articles_search_g2");
        }
    }
}
