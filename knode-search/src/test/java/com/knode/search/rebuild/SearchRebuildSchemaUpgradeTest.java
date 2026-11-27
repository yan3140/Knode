package com.knode.search.rebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.cache.SearchCacheInvalidator;
import com.knode.search.consumer.persistence.ArticleProjectionRecord;
import com.knode.search.consumer.persistence.ArticleVersionBarrier;
import com.knode.search.consumer.persistence.CacheInvalidationStatus;
import com.knode.search.consumer.persistence.IndexGenerationState;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.rebuild.persistence.RebuildJobRecord;
import com.knode.search.rebuild.persistence.RebuildPhase;
import com.knode.search.ops.SearchOpsRepository;
import com.knode.search.rebuild.persistence.SearchRebuildRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

public class SearchRebuildSchemaUpgradeTest {

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

            // 4) Verify new SEARCH-03-R1 rebuild job & candidate generation-2 isolation work immediately on upgraded DB.
            RebuildJobRecord job = tx.execute(status -> rebuildRepo.startJobInCurrentTx(
                    "rb-upgrade-1", "knode_articles_search", "knode_articles_search_g1",
                    "req-upgrade-1", "inst-1", 60_000L, 1700000001000L));
            assertThat(job).isNotNull();
            assertThat(job.phase()).isEqualTo(RebuildPhase.INIT);
            assertThat(job.sourceGeneration()).isEqualTo(1L);
            assertThat(job.candidateGeneration()).isEqualTo(2L);
            assertThat(job.candidatePhysicalIndex()).isEqualTo("knode_articles_search_g2");
            assertThat(job.leaseGeneration()).isEqualTo(1L);
            assertThat(job.aliasSwitchUncertain()).isFalse();
        }
    }

    @Test
    void upgradesSearch03SchemaToSearch03R1PreservingExistingRebuildJobs() {
        String dbName = "search_upgrade_r1_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            // 1) Bootstrap SEARCH-03 schema (before lease_generation & alias_switch_uncertain existed).
            jdbc.execute("""
                    CREATE TABLE search_index_generation (
                        alias_name VARCHAR(128) NOT NULL,
                        active_generation BIGINT NOT NULL DEFAULT 1,
                        active_physical_index VARCHAR(128) NOT NULL,
                        require_cold_visibility_guard TINYINT(1) NOT NULL DEFAULT 0,
                        cutover_state VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
                        cutover_job_id VARCHAR(64) NULL,
                        previous_generation BIGINT NULL,
                        previous_physical_index VARCHAR(128) NULL,
                        target_generation BIGINT NULL,
                        target_physical_index VARCHAR(128) NULL,
                        cutover_started_at_ms BIGINT NULL,
                        active_cache_invalidation_status VARCHAR(16) NOT NULL DEFAULT 'DONE',
                        active_cache_invalidation_attempts INT NOT NULL DEFAULT 0,
                        active_cache_invalidation_due_at_ms BIGINT NULL,
                        active_cache_last_error VARCHAR(512) NULL,
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (alias_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbc.execute("""
                    CREATE TABLE search_rebuild_job (
                        job_id VARCHAR(64) NOT NULL,
                        alias_name VARCHAR(128) NOT NULL,
                        active_lock_key VARCHAR(128) NULL,
                        client_request_id VARCHAR(64) NOT NULL,
                        snapshot_id VARCHAR(64) NULL,
                        snapshot_expires_at_ms BIGINT NULL,
                        snapshot_page_cursor VARCHAR(1024) NULL,
                        snapshot_done TINYINT(1) NOT NULL DEFAULT 0,
                        snapshot_pages_imported INT NOT NULL DEFAULT 0,
                        snapshot_items_imported BIGINT NOT NULL DEFAULT 0,
                        incremental_cursor VARCHAR(1024) NULL,
                        incremental_cursor_expires_at_ms BIGINT NULL,
                        last_commit_sequence BIGINT NOT NULL DEFAULT 0,
                        incremental_commits_applied BIGINT NOT NULL DEFAULT 0,
                        incremental_events_applied BIGINT NOT NULL DEFAULT 0,
                        source_generation BIGINT NOT NULL,
                        source_physical_index VARCHAR(128) NOT NULL,
                        candidate_generation BIGINT NOT NULL,
                        candidate_physical_index VARCHAR(128) NOT NULL,
                        phase VARCHAR(32) NOT NULL,
                        lease_owner VARCHAR(128) NULL,
                        lease_until_ms BIGINT NULL,
                        switch_attempts INT NOT NULL DEFAULT 0,
                        last_error VARCHAR(512) NULL,
                        superseded_by_job_id VARCHAR(64) NULL,
                        created_at_ms BIGINT NOT NULL,
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (job_id),
                        CONSTRAINT uk_search_rebuild_active_alias UNIQUE (active_lock_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            jdbc.update("""
                    INSERT INTO search_rebuild_job (
                        job_id, alias_name, active_lock_key, client_request_id, snapshot_id,
                        snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                        last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                        source_generation, source_physical_index, candidate_generation, candidate_physical_index,
                        phase, lease_owner, lease_until_ms, switch_attempts, created_at_ms, updated_at_ms
                    ) VALUES (
                        'rb-existing-03', 'knode_articles_search', 'knode_articles_search', 'req-03', 'snap-03',
                        0, 1, 2, 0, 0, 0, 1, 'knode_articles_search_g1', 2, 'knode_articles_search_g2',
                        'SNAPSHOT_IMPORTING', 'inst-old', 1700000000000, 0, 1699999990000, 1699999995000
                    )
                    """);

            // 2) Execute additive SEARCH-03 -> SEARCH-03-R1 upgrade SQL.
            ResourceDatabasePopulator r1Populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/search-rebuild-03-r1-upgrade.sql"));
            r1Populator.execute(ds);

            ObjectMapper mapper = new ObjectMapper();
            SearchConsumerRepository consumerRepo = new SearchConsumerRepository(jdbc, mapper);
            SearchRebuildRepository rebuildRepo = new SearchRebuildRepository(jdbc, consumerRepo, mapper);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

            RebuildJobRecord existing = rebuildRepo.findJob("rb-existing-03").orElseThrow();
            assertThat(existing.phase()).isEqualTo(RebuildPhase.SNAPSHOT_IMPORTING);
            assertThat(existing.leaseGeneration()).isEqualTo(0L);
            assertThat(existing.aliasSwitchUncertain()).isFalse();

            RebuildJobRecord leased = tx.execute(status -> rebuildRepo.acquireOrRenewLeaseInCurrentTx(
                    "rb-existing-03", "inst-new", 30_000L, 1700000010000L)).orElseThrow();
            assertThat(leased.leaseOwner()).isEqualTo("inst-new");
            assertThat(leased.leaseGeneration()).isEqualTo(1L);
        }
    }

    @Test
    void upgradesSearch03R2SchemaToSearch03R3PreservingPendingGenerationCacheInvalidation() {
        String dbName = "search_upgrade_r3_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            // 1) Bootstrap SEARCH-03-R1/R2 schema (before active_cutover_bypass_owner existed).
            jdbc.execute("""
                    CREATE TABLE search_index_generation (
                        alias_name VARCHAR(128) NOT NULL,
                        active_generation BIGINT NOT NULL DEFAULT 1,
                        active_physical_index VARCHAR(128) NOT NULL,
                        require_cold_visibility_guard TINYINT(1) NOT NULL DEFAULT 0,
                        cutover_state VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
                        cutover_job_id VARCHAR(64) NULL,
                        previous_generation BIGINT NULL,
                        previous_physical_index VARCHAR(128) NULL,
                        target_generation BIGINT NULL,
                        target_physical_index VARCHAR(128) NULL,
                        cutover_started_at_ms BIGINT NULL,
                        active_cache_invalidation_status VARCHAR(16) NOT NULL DEFAULT 'DONE',
                        active_cache_invalidation_attempts INT NOT NULL DEFAULT 0,
                        active_cache_invalidation_due_at_ms BIGINT NULL,
                        active_cache_last_error VARCHAR(512) NULL,
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (alias_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbc.execute("""
                    CREATE TABLE search_rebuild_job (
                        job_id VARCHAR(64) NOT NULL,
                        alias_name VARCHAR(128) NOT NULL,
                        active_lock_key VARCHAR(128) NULL,
                        client_request_id VARCHAR(64) NOT NULL,
                        snapshot_id VARCHAR(64) NULL,
                        snapshot_expires_at_ms BIGINT NULL,
                        snapshot_page_cursor VARCHAR(1024) NULL,
                        snapshot_done TINYINT(1) NOT NULL DEFAULT 0,
                        snapshot_pages_imported INT NOT NULL DEFAULT 0,
                        snapshot_items_imported BIGINT NOT NULL DEFAULT 0,
                        incremental_cursor VARCHAR(1024) NULL,
                        incremental_cursor_expires_at_ms BIGINT NULL,
                        last_commit_sequence BIGINT NOT NULL DEFAULT 0,
                        incremental_commits_applied BIGINT NOT NULL DEFAULT 0,
                        incremental_events_applied BIGINT NOT NULL DEFAULT 0,
                        source_generation BIGINT NOT NULL,
                        source_physical_index VARCHAR(128) NOT NULL,
                        candidate_generation BIGINT NOT NULL,
                        candidate_physical_index VARCHAR(128) NOT NULL,
                        phase VARCHAR(32) NOT NULL,
                        lease_owner VARCHAR(128) NULL,
                        lease_until_ms BIGINT NULL,
                        lease_generation BIGINT NOT NULL DEFAULT 0,
                        alias_switch_uncertain TINYINT(1) NOT NULL DEFAULT 0,
                        switch_attempts INT NOT NULL DEFAULT 0,
                        last_error VARCHAR(512) NULL,
                        superseded_by_job_id VARCHAR(64) NULL,
                        created_at_ms BIGINT NOT NULL,
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (job_id),
                        CONSTRAINT uk_search_rebuild_active_alias UNIQUE (active_lock_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            jdbc.execute("""
                    CREATE TABLE search_article_version_barrier (
                        article_id BIGINT NOT NULL,
                        max_content_revision BIGINT NOT NULL DEFAULT 0,
                        last_visibility VARCHAR(32) NOT NULL DEFAULT 'NONE',
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (article_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbc.execute("""
                    CREATE TABLE search_article_projection (
                        index_generation BIGINT NOT NULL DEFAULT 1,
                        article_id BIGINT NOT NULL,
                        has_content TINYINT(1) NOT NULL DEFAULT 0,
                        content_revision BIGINT NOT NULL DEFAULT 0,
                        visibility VARCHAR(32) NOT NULL DEFAULT 'NONE',
                        counter_revision BIGINT NOT NULL DEFAULT 0,
                        view_count BIGINT NOT NULL DEFAULT 0,
                        projection_revision BIGINT NOT NULL DEFAULT 0,
                        title VARCHAR(256) NULL,
                        summary VARCHAR(1024) NULL,
                        content LONGTEXT NULL,
                        category_id BIGINT NULL,
                        category_name VARCHAR(256) NULL,
                        tag_names_json TEXT NULL,
                        thumbnail VARCHAR(2048) NULL,
                        is_top VARCHAR(8) NULL,
                        create_time_ms BIGINT NULL,
                        updated_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (index_generation, article_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            // Seed a legacy R2 generation row that is mid-PENDING cache compensation.
            jdbc.update("""
                    INSERT INTO search_index_generation (
                        alias_name, active_generation, active_physical_index,
                        require_cold_visibility_guard, cutover_state,
                        active_cache_invalidation_status, active_cache_invalidation_attempts,
                        active_cache_invalidation_due_at_ms, active_cache_last_error, updated_at_ms
                    ) VALUES (
                        'knode_articles_search', 2, 'knode_articles_search_g2',
                        1, 'NORMAL', 'PENDING', 1, 1700000000000, 'ERR_LEGACY_INCR', 1700000000000
                    )
                    """);

            // 2) Execute additive SEARCH-03-R1/R2 -> SEARCH-03-R3 upgrade SQL.
            ResourceDatabasePopulator r3Populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/search-rebuild-03-r3-upgrade.sql"));
            r3Populator.execute(ds);

            ObjectMapper mapper = new ObjectMapper();
            SearchConsumerRepository consumerRepo = new SearchConsumerRepository(jdbc, mapper);
            SearchRebuildRepository rebuildRepo = new SearchRebuildRepository(jdbc, consumerRepo, mapper);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

            // 3) Legacy PENDING generation row is preserved with null activeCutoverBypassOwner (falls back to R2 owner).
            IndexGenerationState upgraded = consumerRepo.findGenerationState("knode_articles_search").orElseThrow();
            assertThat(upgraded.activeGeneration()).isEqualTo(2L);
            assertThat(upgraded.activeCacheInvalidationStatus()).isEqualTo(CacheInvalidationStatus.PENDING);
            assertThat(upgraded.activeCutoverBypassOwner()).isNull();

            var due = consumerRepo.findDueGenerationCacheInvalidations(1700000005000L);
            assertThat(due).hasSize(1);
            assertThat(due.get(0).bypassOwner()).isNull();

            Boolean markedDone = tx.execute(status -> consumerRepo.markGenerationCacheInvalidationDone(
                    "knode_articles_search", 2L, 1700000006000L));
            assertThat(markedDone).isTrue();

            // 4) New R3 cutover activation persists attempt-scoped owner token and conditional DONE clears it.
            RebuildJobRecord job = tx.execute(status -> rebuildRepo.startJobInCurrentTx(
                    "rb-r3-upgrade", "knode_articles_search", "knode_articles_search_g2",
                    "req-r3-up", "inst-r3", 60_000L, 1700000010000L));
            assertThat(job).isNotNull();
            assertThat(job.candidateGeneration()).isEqualTo(3L);

            String attempt2Owner = SearchCacheInvalidator.cutoverBypassOwnerToken("knode_articles_search", 3L, 2L);
            tx.executeWithoutResult(status -> rebuildRepo.activateCandidateGenerationInCurrentTx(
                    job, true, attempt2Owner, 1700000020000L));

            IndexGenerationState afterActivate = consumerRepo.findGenerationState("knode_articles_search").orElseThrow();
            assertThat(afterActivate.activeGeneration()).isEqualTo(3L);
            assertThat(afterActivate.activeCutoverBypassOwner()).isEqualTo(attempt2Owner);

            var dueR3 = consumerRepo.findDueGenerationCacheInvalidations(1700000025000L);
            assertThat(dueR3).hasSize(1);
            assertThat(dueR3.get(0).bypassOwner()).isEqualTo(attempt2Owner);

            Boolean r3Done = tx.execute(status -> consumerRepo.markGenerationCacheInvalidationDone(
                    "knode_articles_search", 3L, 1700000030000L));
            assertThat(r3Done).isTrue();
            assertThat(consumerRepo.findGenerationState("knode_articles_search").orElseThrow().activeCutoverBypassOwner())
                    .isNull();
        }
    }

    @Test
    void upgradesSearch03R3SchemaToSearch04AddingOpsReplayAuditTable() {
        String dbName = "search_upgrade_04_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            // Bootstrap complete SEARCH-03-R3 schema by running base consumer schema minus search_ops_replay_audit.
            ResourceDatabasePopulator basePopulator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/search-consumer-schema.sql"));
            basePopulator.execute(ds);
            jdbc.execute("DROP TABLE search_ops_replay_audit");

            // Seed existing SEARCH-03-R3 task & inbox rows.
            jdbc.update("""
                    INSERT INTO search_event_inbox (event_id, event_type, schema_version, aggregate_type, aggregate_id, source_revision, stream_record_id, status, received_at_ms, processed_at_ms)
                    VALUES ('11111111-1111-4111-8111-111111111111', 'ArticlePublished', 1, 'article', '7001', 2, '1-0', 'APPLIED', 1700000000000, 1700000000000)
                    """);
            jdbc.update("""
                    INSERT INTO search_index_task (index_generation, target_index, article_id, target_projection_revision, operation, payload_json, status, attempts, max_attempts, due_at_ms, claim_generation, es_applied_revision, cache_invalidation_status, cache_invalidation_attempts, updated_at_ms)
                    VALUES (1, 'knode_articles_search_g1', 7001, 2, 'UPSERT', '{"id":7001,"searchable":true}', 'DEAD', 5, 5, 1700000000000, 1, 0, 'NONE', 0, 1700000000000)
                    """);

            // Execute additive SEARCH-04 upgrade SQL.
            ResourceDatabasePopulator upgrade04 = new ResourceDatabasePopulator(
                    new ClassPathResource("db/search-ops-04-upgrade.sql"));
            upgrade04.execute(ds);

            ObjectMapper mapper = new ObjectMapper();
            SearchConsumerRepository consumerRepo = new SearchConsumerRepository(jdbc, mapper);
            SearchOpsRepository opsRepo = new SearchOpsRepository(jdbc, consumerRepo);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

            SearchOpsRepository.OpsSummaryDbSnapshot summary = opsRepo.loadSummarySnapshot(
                    "knode_articles_search", "knode_articles_search_g1", 1700000010000L);
            assertThat(summary.taskCounts().get("DEAD")).isEqualTo(1L);

            Boolean inserted = tx.execute(status -> opsRepo.insertReplayAudit(new SearchOpsRepository.ReplayAuditRecord(
                    "req-upgrade-04",
                    "SINGLE_RETRY",
                    "11111111-1111-4111-8111-111111111111",
                    "abc123",
                    "knode-content",
                    "ops-admin",
                    "ACCEPTED",
                    1L,
                    "{\"status\":\"ACCEPTED\"}",
                    1700000010000L
            )));
            assertThat(inserted).isTrue();
            assertThat(opsRepo.findReplayAuditForUpdate("req-upgrade-04")).isPresent();
        }
    }

    @Test
    void upgradesSearch04R1SchemaToSearch04R2EnablingCaseExactReplayKeys() {
        String dbName = "search_upgrade_04_r2_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.execute("SET COLLATION ENGLISH STRENGTH PRIMARY");

            // Bootstrap legacy SEARCH-04 / SEARCH-04-R1 audit table (VARCHAR PK inheriting default CI collation).
            jdbc.execute("""
                    CREATE TABLE search_ops_replay_audit (
                        replay_request_id VARCHAR(64) NOT NULL,
                        operation_type VARCHAR(32) NOT NULL,
                        target_event_id VARCHAR(128) NULL,
                        request_fingerprint CHAR(64) NOT NULL,
                        caller_service VARCHAR(128) NOT NULL,
                        operator_id VARCHAR(128) NULL,
                        response_status VARCHAR(32) NOT NULL,
                        requeued_count BIGINT NOT NULL DEFAULT 0,
                        response_json TEXT NOT NULL,
                        created_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (replay_request_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbc.execute("CREATE INDEX idx_search_ops_replay_time ON search_ops_replay_audit (created_at_ms)");
            jdbc.update("""
                    INSERT INTO search_ops_replay_audit (
                        replay_request_id, operation_type, target_event_id, request_fingerprint,
                        caller_service, operator_id, response_status, requeued_count, response_json, created_at_ms
                    ) VALUES (
                        'ReviewCaseKey', 'BATCH_RETRY', NULL, 'fp-upper',
                        'knode-content', 'ops-admin', 'ACCEPTED', 1, '{"status":"ACCEPTED","replayRequestId":"ReviewCaseKey"}', 1700000010000
                    )
                    """);

            // Apply SEARCH-04/R1 -> SEARCH-04-R2 upgrade SQL.
            executeMySqlR2UpgradeScriptOnH2(jdbc);

            ObjectMapper mapper = new ObjectMapper();
            SearchConsumerRepository consumerRepo = new SearchConsumerRepository(jdbc, mapper);
            SearchOpsRepository opsRepo = new SearchOpsRepository(jdbc, consumerRepo);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

            SearchOpsRepository.ReplayAuditRecord preserved = opsRepo.findReplayAudit("ReviewCaseKey").orElseThrow();
            assertThat(preserved.replayRequestId()).isEqualTo("ReviewCaseKey");
            assertThat(preserved.requestFingerprint()).isEqualTo("fp-upper");
            assertThat(preserved.callerService()).isEqualTo("knode-content");
            assertThat(preserved.operatorId()).isEqualTo("ops-admin");
            assertThat(preserved.createdAtMs()).isEqualTo(1700000010000L);
            assertThat(opsRepo.findReplayAudit("reviewcasekey")).isEmpty();

            Boolean insertedLower = tx.execute(status -> opsRepo.insertReplayAudit(new SearchOpsRepository.ReplayAuditRecord(
                    "reviewcasekey", "BATCH_RETRY", null, "fp-lower",
                    "knode-content", "ops-admin", "ACCEPTED", 1L,
                    "{\"status\":\"ACCEPTED\",\"replayRequestId\":\"reviewcasekey\"}", 1700000020000L
            )));
            assertThat(insertedLower).isTrue();
            assertThat(opsRepo.findReplayAudit("ReviewCaseKey").orElseThrow().requestFingerprint()).isEqualTo("fp-upper");
            assertThat(opsRepo.findReplayAudit("reviewcasekey").orElseThrow().requestFingerprint()).isEqualTo("fp-lower");
        }
    }

    @Test
    void r2UpgradeSqlSwitchesPrimaryKeyAtomicallyInSingleAlterTableStatement() throws Exception {
        List<String> statements = loadSqlStatements("db/search-ops-04-r2-upgrade.sql");
        assertThat(statements).hasSize(4);
        assertThat(statements.get(0)).containsIgnoringCase("ADD COLUMN replay_request_key VARBINARY(64) NULL");
        assertThat(statements.get(1)).containsIgnoringCase("SET replay_request_key = replay_request_id");
        assertThat(statements.get(2)).containsIgnoringCase("MODIFY COLUMN replay_request_key VARBINARY(64) NOT NULL");

        // Must combine DROP PRIMARY KEY and ADD PRIMARY KEY (replay_request_key) in ONE ALTER TABLE
        // so MySQL 8 sql_require_primary_key = ON succeeds without Error 3750.
        String pkSwapStmt = statements.get(3).replaceAll("\\s+", " ").trim();
        assertThat(pkSwapStmt).containsIgnoringCase("ALTER TABLE search_ops_replay_audit");
        assertThat(pkSwapStmt).containsIgnoringCase("DROP PRIMARY KEY, ADD PRIMARY KEY (replay_request_key)");
        assertThat(statements.stream()
                .filter(s -> s.toUpperCase().contains("DROP PRIMARY KEY") && !s.toUpperCase().contains("ADD PRIMARY KEY"))
                .count()).isZero();
    }

    @Test
    void upgradesSearch03LegacyPollutedVersionBarriersViaR6UpgradeSqlIdempotently() {
        String dbName = "search_upgrade_r6_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:" + dbName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        hc.setDriverClassName("org.h2.Driver");
        hc.setUsername("sa");
        hc.setPassword("");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            ResourceDatabasePopulator basePopulator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/search-consumer-schema.sql"));
            basePopulator.execute(ds);
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            long t0 = 1700000000000L;
            // Generation state: g1 -> g3 (COMPLETED), while g2 FAILED (abandoned, g2 < active_generation=3!)
            // and g4 is currently SNAPSHOT_IMPORTING (unactivated).
            jdbc.update("""
                    INSERT INTO search_index_generation (
                        alias_name, active_generation, active_physical_index, require_cold_visibility_guard,
                        cutover_state, previous_generation, previous_physical_index,
                        active_cache_invalidation_status, active_cache_invalidation_attempts, updated_at_ms
                    ) VALUES (
                        'knode_articles_search', 3, 'knode_articles_search_g3', 1,
                        'NORMAL', 1, 'knode_articles_search_g1', 'DONE', 0, ?
                    )
                    """, t0);
            jdbc.update("""
                    INSERT INTO search_rebuild_job (
                        job_id, alias_name, active_lock_key, client_request_id,
                        snapshot_done, snapshot_pages_imported, snapshot_items_imported,
                        last_commit_sequence, incremental_commits_applied, incremental_events_applied,
                        source_generation, source_physical_index, candidate_generation, candidate_physical_index,
                        phase, lease_owner, lease_until_ms, lease_generation,
                        alias_switch_uncertain, switch_attempts, created_at_ms, updated_at_ms
                    ) VALUES
                        ('rb-g2-failed', 'knode_articles_search', NULL, 'req-g2',
                         1, 1, 2, 0, 0, 0, 1, 'knode_articles_search_g1', 2, 'knode_articles_search_g2',
                         'FAILED', 'i1', ?, 1, 0, 0, ?, ?),
                        ('rb-g3-done', 'knode_articles_search', NULL, 'req-g3',
                         1, 1, 2, 0, 0, 0, 1, 'knode_articles_search_g1', 3, 'knode_articles_search_g3',
                         'COMPLETED', 'i1', ?, 1, 0, 1, ?, ?),
                        ('rb-g4-snap', 'knode_articles_search', 'knode_articles_search', 'req-g4',
                         0, 1, 3, 0, 0, 0, 3, 'knode_articles_search_g3', 4, 'knode_articles_search_g4',
                         'SNAPSHOT_IMPORTING', 'i1', ?, 1, 0, 0, ?, ?)
                    """, t0, t0, t0, t0, t0, t0, t0, t0, t0);

            // Activated projections (g1, g3):
            // 101: g3 PUBLIC v1; 102: g3 PUBLIC v1; 103: g1 DELETED v5 (prior activated delete barrier);
            // 106: g3 PUBLIC v3 (missing barrier -> must be backfilled!).
            jdbc.update("""
                    INSERT INTO search_article_projection (
                        index_generation, article_id, has_content, content_revision, visibility,
                        counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms
                    ) VALUES
                        (3, 101, 1, 1, 'PUBLIC', 0, 1, 1, 'A101', 'S', 'B', ?),
                        (3, 102, 1, 1, 'PUBLIC', 0, 1, 1, 'A102', 'S', 'B', ?),
                        (1, 103, 1, 5, 'DELETED', 0, 0, 2, NULL, NULL, NULL, ?),
                        (3, 106, 1, 3, 'PUBLIC', 0, 5, 1, 'A106', 'S', 'B', ?)
                    """, t0, t0, t0, t0);

            // Unactivated candidate projections (abandoned g2 & in-progress g4):
            // 101: g4 PUBLIC v2 (polluted!); 102: g2 DELETED v2 (polluted from g2 < active 3!);
            // 104: g4 PUBLIC v1 (brand new, purely candidate-polluted -> must be deleted!).
            jdbc.update("""
                    INSERT INTO search_article_projection (
                        index_generation, article_id, has_content, content_revision, visibility,
                        counter_revision, view_count, projection_revision, title, summary, content, updated_at_ms
                    ) VALUES
                        (4, 101, 1, 2, 'PUBLIC', 0, 1, 1, 'A101 V2', 'S', 'B', ?),
                        (2, 102, 1, 2, 'DELETED', 0, 0, 1, NULL, NULL, NULL, ?),
                        (4, 104, 1, 1, 'PUBLIC', 0, 1, 1, 'A104 New', 'S', 'B', ?)
                    """, t0 + 100, t0 + 100, t0 + 100);

            // Seed polluted + valid barriers (105 is standalone DELETED v8 with no projection in any gen).
            jdbc.update("""
                    INSERT INTO search_article_version_barrier (
                        article_id, max_content_revision, last_visibility, updated_at_ms
                    ) VALUES
                        (101, 2, 'PUBLIC', ?),
                        (102, 2, 'DELETED', ?),
                        (103, 5, 'DELETED', ?),
                        (104, 1, 'PUBLIC', ?),
                        (105, 8, 'DELETED', ?)
                    """, t0 + 100, t0 + 100, t0, t0 + 100, t0);

            ResourceDatabasePopulator r6Populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/search-rebuild-03-r6-upgrade.sql"));
            r6Populator.execute(ds);
            // Re-run idempotently to confirm zero errors and stable final state.
            r6Populator.execute(ds);

            SearchConsumerRepository consumerRepo = new SearchConsumerRepository(jdbc, new ObjectMapper());
            // 101 & 102 repaired strictly back to activated g3's (1, PUBLIC)!
            assertThat(consumerRepo.findVersionBarrier(101L).orElseThrow())
                    .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                    .containsExactly(1L, ArticleVisibility.PUBLIC);
            assertThat(consumerRepo.findVersionBarrier(102L).orElseThrow())
                    .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                    .containsExactly(1L, ArticleVisibility.PUBLIC);
            // 103 (prior activated g1 DELETED v5) strictly preserved!
            assertThat(consumerRepo.findVersionBarrier(103L).orElseThrow())
                    .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                    .containsExactly(5L, ArticleVisibility.DELETED);
            // 104 (purely candidate-polluted) deleted!
            assertThat(consumerRepo.findVersionBarrier(104L)).isEmpty();
            // 105 (standalone legacy DELETED v8) strictly preserved!
            assertThat(consumerRepo.findVersionBarrier(105L).orElseThrow())
                    .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                    .containsExactly(8L, ArticleVisibility.DELETED);
            // 106 (missing activated g3 projection) backfilled!
            assertThat(consumerRepo.findVersionBarrier(106L).orElseThrow())
                    .extracting(ArticleVersionBarrier::maxContentRevision, ArticleVersionBarrier::lastVisibility)
                    .containsExactly(3L, ArticleVisibility.PUBLIC);
        }
    }

    /**
     * Executes db/search-ops-04-r2-upgrade.sql on H2 (MODE=MySQL).
     * H2 2.3.x does not parse MySQL's comma-chained "DROP PRIMARY KEY, ADD PRIMARY KEY (...)"
     * inside a single ALTER TABLE statement, so this test-only helper asserts that the production
     * SQL uses the single-statement MySQL 8 form (compatible with sql_require_primary_key=ON)
     * and splits only that final clause for H2 execution.
     */
    public static void executeMySqlR2UpgradeScriptOnH2(JdbcTemplate jdbc) {
        try {
            List<String> statements = loadSqlStatements("db/search-ops-04-r2-upgrade.sql");
            Pattern atomicPkSwap = Pattern.compile(
                    "(?is)^ALTER\\s+TABLE\\s+search_ops_replay_audit\\s+DROP\\s+PRIMARY\\s+KEY\\s*,\\s*ADD\\s+PRIMARY\\s+KEY\\s*\\(\\s*replay_request_key\\s*\\)$");
            boolean sawAtomicSwap = false;
            for (String stmt : statements) {
                if (atomicPkSwap.matcher(stmt.trim()).matches()) {
                    sawAtomicSwap = true;
                    jdbc.execute("ALTER TABLE search_ops_replay_audit DROP PRIMARY KEY");
                    jdbc.execute("ALTER TABLE search_ops_replay_audit ADD PRIMARY KEY (replay_request_key)");
                } else {
                    jdbc.execute(stmt);
                }
            }
            assertThat(sawAtomicSwap).as("Expected single-ALTER DROP+ADD PRIMARY KEY in R2 upgrade SQL").isTrue();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to apply R2 upgrade SQL on H2", ex);
        }
    }

    private static List<String> loadSqlStatements(String classpathLocation) throws Exception {
        String raw = new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);
        String withoutComments = raw.replaceAll("(?m)^\\s*--.*$", "");
        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }
}
