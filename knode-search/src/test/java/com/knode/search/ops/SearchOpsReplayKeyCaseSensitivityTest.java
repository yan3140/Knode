package com.knode.search.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.rebuild.SearchRebuildSchemaUpgradeTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchOpsReplayKeyCaseSensitivityTest {
    private static final String ALIAS = "knode_it_r2_articles_search";
    private static final String PHYSICAL_G1 = ALIAS + "_g1";
    private static final long BASE_NOW_MS = 1_760_000_000_000L;

    @Test
    void caseVariantReplayRequestIdsExecuteIndependentlyAndReplayExactResponses() {
        for (boolean useIgnoreCaseUrl : new boolean[]{false, true}) {
            try (TestEnv env = createFreshCaseInsensitiveEnv(useIgnoreCaseUrl)) {
                String evt1 = UUID.randomUUID().toString();
                String evt2 = UUID.randomUUID().toString();
                insertInbox(env.jdbc, evt1, "8001", "APPLIED", BASE_NOW_MS - 6000);
                insertDeadTask(env.jdbc, 8001L, 1L, "ES_TIMEOUT_1", BASE_NOW_MS - 6000);
                insertInbox(env.jdbc, evt2, "8002", "APPLIED", BASE_NOW_MS - 5000);
                insertDeadTask(env.jdbc, 8002L, 1L, "ES_TIMEOUT_2", BASE_NOW_MS - 5000);

                SearchConsumerOpsService service = env.newOpsService(new SearchOpsRepository(env.jdbc, env.consumerRepo));

                // 1) ReviewCaseKey and reviewcasekey each execute once with limit=1
                Map<String, Object> upperResp = service.retryBatch(env.caller, "ReviewCaseKey", "ops-1", 1);
                Map<String, Object> lowerResp = service.retryBatch(env.caller, "reviewcasekey", "ops-1", 1);

                assertThat(upperResp.get("status")).isEqualTo("ACCEPTED");
                assertThat(upperResp.get("replayRequestId")).isEqualTo("ReviewCaseKey");
                assertThat(upperResp.get("requeued")).isEqualTo("1");

                assertThat(lowerResp.get("status")).isEqualTo("ACCEPTED");
                assertThat(lowerResp.get("replayRequestId")).isEqualTo("reviewcasekey");
                assertThat(lowerResp.get("requeued")).isEqualTo("1");

                SearchIndexTaskRecord t1 = env.consumerRepo.findIndexTask(1L, 8001L).orElseThrow();
                SearchIndexTaskRecord t2 = env.consumerRepo.findIndexTask(1L, 8002L).orElseThrow();
                assertThat(t1.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
                assertThat(t1.attempts()).isEqualTo(0);
                assertThat(t1.claimGeneration()).isEqualTo(2L);
                assertThat(t2.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
                assertThat(t2.attempts()).isEqualTo(0);
                assertThat(t2.claimGeneration()).isEqualTo(2L);

                assertThat(env.jdbc.queryForObject("SELECT COUNT(*) FROM search_ops_replay_audit", Long.class))
                        .isEqualTo(2L);
                assertThat(countAuditByExactKey(env.jdbc, "ReviewCaseKey")).isEqualTo(1L);
                assertThat(countAuditByExactKey(env.jdbc, "reviewcasekey")).isEqualTo(1L);
                assertThat(listAuditIds(env.jdbc)).containsExactlyInAnyOrder("ReviewCaseKey", "reviewcasekey");

                // 2) Replaying each ID returns its own committed response with ZERO additional DB mutation
                Map<String, Object> upperReplay = service.retryBatch(env.caller, "ReviewCaseKey", "ops-1", 1);
                Map<String, Object> lowerReplay = service.retryBatch(env.caller, "reviewcasekey", "ops-1", 1);
                assertThat(upperReplay).isEqualTo(upperResp);
                assertThat(lowerReplay).isEqualTo(lowerResp);
                assertThat(env.consumerRepo.findIndexTask(1L, 8001L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(env.consumerRepo.findIndexTask(1L, 8002L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(env.jdbc.queryForObject("SELECT COUNT(*) FROM search_ops_replay_audit", Long.class))
                        .isEqualTo(2L);

                // 3) Parameter conflict on ReviewCaseKey returns 409 without disturbing reviewcasekey or tasks
                assertThatThrownBy(() -> service.retryBatch(env.caller, "ReviewCaseKey", "ops-1", 2))
                        .isInstanceOf(InternalOpsException.class)
                        .satisfies(ex -> {
                            InternalOpsException opsEx = (InternalOpsException) ex;
                            assertThat(opsEx.httpStatus()).isEqualTo(409);
                            assertThat(opsEx.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                        });
                assertThat(service.retryBatch(env.caller, "reviewcasekey", "ops-1", 1)).isEqualTo(lowerResp);

                // 4) Single-event retry with case-variant IDs executes independently and preserves distinct responses
                String evtSingle = UUID.randomUUID().toString();
                insertInbox(env.jdbc, evtSingle, "8003", "APPLIED", BASE_NOW_MS - 4000);
                insertDeadTask(env.jdbc, 8003L, 1L, "ES_SINGLE_ERR", BASE_NOW_MS - 4000);

                Map<String, Object> singleUpper = service.retrySingleEvent(env.caller, evtSingle, "SingleCaseKey", "ops-1");
                Map<String, Object> singleLower = service.retrySingleEvent(env.caller, evtSingle, "singlecasekey", "ops-1");
                assertThat(singleUpper.get("status")).isEqualTo("ACCEPTED");
                assertThat(singleUpper.get("replayRequestId")).isEqualTo("SingleCaseKey");
                assertThat(singleUpper.get("requeued")).isEqualTo("1");
                assertThat(singleLower.get("status")).isEqualTo("NOOP");
                assertThat(singleLower.get("replayRequestId")).isEqualTo("singlecasekey");
                assertThat(singleLower.get("requeued")).isEqualTo("0");
                assertThat(env.consumerRepo.findIndexTask(1L, 8003L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(service.retrySingleEvent(env.caller, evtSingle, "SingleCaseKey", "ops-1")).isEqualTo(singleUpper);
                assertThat(service.retrySingleEvent(env.caller, evtSingle, "singlecasekey", "ops-1")).isEqualTo(singleLower);

                // 5) Boundary legal IDs: 1-char, hex-like, and max 64-char mixed vs lowercase
                String max64Mixed = "AbCdEf0123456789._-".repeat(3) + "XyZ1234";
                assertThat(max64Mixed).hasSize(64);
                String max64Lower = max64Mixed.toLowerCase();
                for (String[] pair : new String[][]{{"A", "a"}, {"deadbeef", "DEADBEEF"}, {max64Mixed, max64Lower}}) {
                    Map<String, Object> first = service.retryBatch(env.caller, pair[0], "ops-1", 1);
                    Map<String, Object> second = service.retryBatch(env.caller, pair[1], "ops-1", 1);
                    assertThat(first.get("replayRequestId")).isEqualTo(pair[0]);
                    assertThat(second.get("replayRequestId")).isEqualTo(pair[1]);
                    assertThat(countAuditByExactKey(env.jdbc, pair[0])).isEqualTo(1L);
                    assertThat(countAuditByExactKey(env.jdbc, pair[1])).isEqualTo(1L);
                }
            }
        }
    }

    @Test
    void legacyCaseInsensitiveAuditTableUpgradesPreservingAuditRowsAndDistinguishingCaseVariants() {
        for (boolean useIgnoreCaseUrl : new boolean[]{false, true}) {
            try (TestEnv env = createLegacySearch04CaseInsensitiveEnv(useIgnoreCaseUrl)) {
                String legacyFingerprint = sha256("BATCH_RETRY\n\nknode-content\nops-legacy\n1");
                String legacyResponseJson = """
                        {"status":"ACCEPTED","replayRequestId":"ReviewCaseKey","requeued":"1","skippedActiveLease":"0","cacheCompensationsExpedited":"0","callerService":"knode-content","operator":"ops-legacy"}
                        """.trim();
                long legacyCreatedAt = BASE_NOW_MS - 60_000L;

                // Seed completed SEARCH-04 / SEARCH-04-R1 audit rows (including a hex-like ID)
                env.jdbc.update("""
                        INSERT INTO search_ops_replay_audit (
                            replay_request_id, operation_type, target_event_id, request_fingerprint,
                            caller_service, operator_id, response_status, requeued_count, response_json, created_at_ms
                        ) VALUES (?, 'BATCH_RETRY', NULL, ?, 'knode-content', 'ops-legacy', 'ACCEPTED', 1, ?, ?)
                        """, "ReviewCaseKey", legacyFingerprint, legacyResponseJson, legacyCreatedAt);

                String hexLegacyFingerprint = sha256("BATCH_RETRY\n\nknode-content\nops-legacy\n1");
                String hexLegacyJson = """
                        {"status":"ACCEPTED","replayRequestId":"deadbeef","requeued":"1","skippedActiveLease":"0","cacheCompensationsExpedited":"0","callerService":"knode-content","operator":"ops-legacy"}
                        """.trim();
                env.jdbc.update("""
                        INSERT INTO search_ops_replay_audit (
                            replay_request_id, operation_type, target_event_id, request_fingerprint,
                            caller_service, operator_id, response_status, requeued_count, response_json, created_at_ms
                        ) VALUES (?, 'BATCH_RETRY', NULL, ?, 'knode-content', 'ops-legacy', 'ACCEPTED', 1, ?, ?)
                        """, "deadbeef", hexLegacyFingerprint, hexLegacyJson, legacyCreatedAt + 100L);

                // Confirm legacy table genuinely folds case before upgrade
                assertThatThrownBy(() -> env.jdbc.update("""
                        INSERT INTO search_ops_replay_audit (
                            replay_request_id, operation_type, target_event_id, request_fingerprint,
                            caller_service, operator_id, response_status, requeued_count, response_json, created_at_ms
                        ) VALUES ('reviewcasekey', 'BATCH_RETRY', NULL, ?, 'knode-content', 'ops-legacy', 'ACCEPTED', 1, '{}', ?)
                        """, legacyFingerprint, legacyCreatedAt + 200L))
                        .isInstanceOf(DataIntegrityViolationException.class);

                insertDeadTask(env.jdbc, 8101L, 1L, "ES_LEGACY_1", BASE_NOW_MS - 5000);
                insertDeadTask(env.jdbc, 8102L, 1L, "ES_LEGACY_2", BASE_NOW_MS - 4000);

                // Apply SEARCH-04 / SEARCH-04-R1 -> SEARCH-04-R2 upgrade SQL
                SearchRebuildSchemaUpgradeTest.executeMySqlR2UpgradeScriptOnH2(env.jdbc);

                // Verify legacy rows, fingerprints, operator/caller metadata, responses, timestamps & exact key bytes preserved
                SearchOpsRepository opsRepo = new SearchOpsRepository(env.jdbc, env.consumerRepo);
                SearchOpsRepository.ReplayAuditRecord preserved = opsRepo.findReplayAudit("ReviewCaseKey").orElseThrow();
                assertThat(preserved.replayRequestId()).isEqualTo("ReviewCaseKey");
                assertThat(preserved.operationType()).isEqualTo("BATCH_RETRY");
                assertThat(preserved.targetEventId()).isNull();
                assertThat(preserved.requestFingerprint()).isEqualTo(legacyFingerprint);
                assertThat(preserved.callerService()).isEqualTo("knode-content");
                assertThat(preserved.operatorId()).isEqualTo("ops-legacy");
                assertThat(preserved.responseStatus()).isEqualTo("ACCEPTED");
                assertThat(preserved.requeuedCount()).isEqualTo(1L);
                assertThat(preserved.responseJson()).isEqualTo(legacyResponseJson);
                assertThat(preserved.createdAtMs()).isEqualTo(legacyCreatedAt);
                assertThat(opsRepo.findReplayAudit("deadbeef")).isPresent();
                assertThat(opsRepo.findReplayAudit("reviewcasekey")).isEmpty();
                assertThat(opsRepo.findReplayAudit("DEADBEEF")).isEmpty();

                SearchConsumerOpsService service = env.newOpsService(opsRepo);

                // 1) Replay original legacy ID -> returns original response with zero task mutation
                Map<String, Object> legacyReplay = service.retryBatch(env.caller, "ReviewCaseKey", "ops-legacy", 1);
                assertThat(legacyReplay.get("status")).isEqualTo("ACCEPTED");
                assertThat(legacyReplay.get("replayRequestId")).isEqualTo("ReviewCaseKey");
                assertThat(legacyReplay.get("operator")).isEqualTo("ops-legacy");
                assertThat(legacyReplay.get("requeued")).isEqualTo("1");
                assertThat(env.consumerRepo.findIndexTask(1L, 8101L).orElseThrow().status())
                        .isEqualTo(SearchIndexTaskStatus.DEAD);
                assertThat(env.consumerRepo.findIndexTask(1L, 8102L).orElseThrow().status())
                        .isEqualTo(SearchIndexTaskStatus.DEAD);

                // 2) Conflicting replay on legacy ID still returns 409 without mutating tasks
                assertThatThrownBy(() -> service.retryBatch(env.caller, "ReviewCaseKey", "ops-legacy", 2))
                        .isInstanceOf(InternalOpsException.class)
                        .satisfies(ex -> {
                            InternalOpsException opsEx = (InternalOpsException) ex;
                            assertThat(opsEx.httpStatus()).isEqualTo(409);
                            assertThat(opsEx.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                        });
                assertThat(env.consumerRepo.findIndexTask(1L, 8101L).orElseThrow().status())
                        .isEqualTo(SearchIndexTaskStatus.DEAD);

                // 3) Case-variant new IDs execute independently on upgraded table
                Map<String, Object> lowerResp = service.retryBatch(env.caller, "reviewcasekey", "ops-legacy", 1);
                assertThat(lowerResp.get("status")).isEqualTo("ACCEPTED");
                assertThat(lowerResp.get("replayRequestId")).isEqualTo("reviewcasekey");
                assertThat(lowerResp.get("requeued")).isEqualTo("1");
                assertThat(env.consumerRepo.findIndexTask(1L, 8101L).orElseThrow().status())
                        .isEqualTo(SearchIndexTaskStatus.PENDING);
                assertThat(env.consumerRepo.findIndexTask(1L, 8101L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(env.consumerRepo.findIndexTask(1L, 8102L).orElseThrow().status())
                        .isEqualTo(SearchIndexTaskStatus.DEAD);

                Map<String, Object> upperHexResp = service.retryBatch(env.caller, "DEADBEEF", "ops-legacy", 1);
                assertThat(upperHexResp.get("status")).isEqualTo("ACCEPTED");
                assertThat(upperHexResp.get("replayRequestId")).isEqualTo("DEADBEEF");
                assertThat(upperHexResp.get("requeued")).isEqualTo("1");
                assertThat(env.consumerRepo.findIndexTask(1L, 8102L).orElseThrow().status())
                        .isEqualTo(SearchIndexTaskStatus.PENDING);
                assertThat(env.consumerRepo.findIndexTask(1L, 8102L).orElseThrow().claimGeneration()).isEqualTo(2L);

                assertThat(listAuditIds(env.jdbc))
                        .containsExactlyInAnyOrder("ReviewCaseKey", "reviewcasekey", "deadbeef", "DEADBEEF");
                assertThat(service.retryBatch(env.caller, "ReviewCaseKey", "ops-legacy", 1)).isEqualTo(legacyReplay);
                assertThat(service.retryBatch(env.caller, "reviewcasekey", "ops-legacy", 1)).isEqualTo(lowerResp);
                assertThat(service.retryBatch(env.caller, "DEADBEEF", "ops-legacy", 1)).isEqualTo(upperHexResp);
            }
        }

        // Also verify SEARCH-03-R3/R5 -> SEARCH-04 upgrade path (db/search-ops-04-upgrade.sql) under case-insensitive collation
        try (TestEnv env = createSearch03R3CaseInsensitiveEnv()) {
            new ResourceDatabasePopulator(new ClassPathResource("db/search-ops-04-upgrade.sql")).execute(env.ds);
            insertDeadTask(env.jdbc, 8151L, 1L, "ES_03R3_1", BASE_NOW_MS - 5000);
            insertDeadTask(env.jdbc, 8152L, 1L, "ES_03R3_2", BASE_NOW_MS - 4000);

            SearchConsumerOpsService service = env.newOpsService(new SearchOpsRepository(env.jdbc, env.consumerRepo));
            Map<String, Object> r1 = service.retryBatch(env.caller, "ReviewCaseKey", "ops-1", 1);
            Map<String, Object> r2 = service.retryBatch(env.caller, "reviewcasekey", "ops-1", 1);
            assertThat(r1.get("replayRequestId")).isEqualTo("ReviewCaseKey");
            assertThat(r2.get("replayRequestId")).isEqualTo("reviewcasekey");
            assertThat(env.consumerRepo.findIndexTask(1L, 8151L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(env.consumerRepo.findIndexTask(1L, 8152L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(listAuditIds(env.jdbc)).containsExactlyInAnyOrder("ReviewCaseKey", "reviewcasekey");
        }
    }

    @Test
    void concurrentSameKeyAndCaseVariantRetriesOnUpgradedCaseInsensitiveTablePreserveIdempotencyConflictAndRollback() throws Exception {
        try (TestEnv env = createLegacySearch04CaseInsensitiveEnv(false)) {
            SearchRebuildSchemaUpgradeTest.executeMySqlR2UpgradeScriptOnH2(env.jdbc);

            // Sub-case A & B: Two concurrent txs on ReviewCaseKey (limit=1), then two concurrent txs on reviewcasekey (limit=1)
            insertDeadTask(env.jdbc, 8201L, 1L, "ERR_1", BASE_NOW_MS - 9000);
            insertDeadTask(env.jdbc, 8202L, 1L, "ERR_2", BASE_NOW_MS - 8000);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CyclicBarrier b1 = new CyclicBarrier(2);
                AtomicInteger h1 = new AtomicInteger();
                SearchConsumerOpsService upperA = env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, b1, h1, 2));
                SearchConsumerOpsService upperB = env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, b1, h1, 2));

                Future<Map<String, Object>> fU1 = pool.submit(() -> upperA.retryBatch(env.caller, "ReviewCaseKey", "ops-1", 1));
                Future<Map<String, Object>> fU2 = pool.submit(() -> upperB.retryBatch(env.caller, "ReviewCaseKey", "ops-1", 1));
                Map<String, Object> rU1 = fU1.get(15, TimeUnit.SECONDS);
                Map<String, Object> rU2 = fU2.get(15, TimeUnit.SECONDS);
                assertThat(rU1.get("replayRequestId")).isEqualTo("ReviewCaseKey");
                assertThat(rU1.get("requeued")).isEqualTo("1");
                assertThat(rU2).isEqualTo(rU1);

                assertThat(env.consumerRepo.findIndexTask(1L, 8201L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.PENDING);
                assertThat(env.consumerRepo.findIndexTask(1L, 8201L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(env.consumerRepo.findIndexTask(1L, 8202L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.DEAD);
                assertThat(env.consumerRepo.findIndexTask(1L, 8202L).orElseThrow().claimGeneration()).isEqualTo(1L);
                assertThat(countAuditByExactKey(env.jdbc, "ReviewCaseKey")).isEqualTo(1L);

                CyclicBarrier b2 = new CyclicBarrier(2);
                AtomicInteger h2 = new AtomicInteger();
                SearchConsumerOpsService lowerA = env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, b2, h2, 2));
                SearchConsumerOpsService lowerB = env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, b2, h2, 2));

                Future<Map<String, Object>> fL1 = pool.submit(() -> lowerA.retryBatch(env.caller, "reviewcasekey", "ops-1", 1));
                Future<Map<String, Object>> fL2 = pool.submit(() -> lowerB.retryBatch(env.caller, "reviewcasekey", "ops-1", 1));
                Map<String, Object> rL1 = fL1.get(15, TimeUnit.SECONDS);
                Map<String, Object> rL2 = fL2.get(15, TimeUnit.SECONDS);
                assertThat(rL1.get("replayRequestId")).isEqualTo("reviewcasekey");
                assertThat(rL1.get("requeued")).isEqualTo("1");
                assertThat(rL2).isEqualTo(rL1);

                assertThat(env.consumerRepo.findIndexTask(1L, 8202L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.PENDING);
                assertThat(env.consumerRepo.findIndexTask(1L, 8202L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(countAuditByExactKey(env.jdbc, "reviewcasekey")).isEqualTo(1L);

                // Sub-case C: 4 concurrent threads (2 x ParallelCase + 2 x parallelcase) racing simultaneously on 2 DEAD tasks
                insertDeadTask(env.jdbc, 8203L, 1L, "ERR_3", BASE_NOW_MS - 7000);
                insertDeadTask(env.jdbc, 8204L, 1L, "ERR_4", BASE_NOW_MS - 6000);

                CyclicBarrier b4 = new CyclicBarrier(4);
                AtomicInteger h4 = new AtomicInteger();
                Future<Map<String, Object>> pU1 = pool.submit(() ->
                        env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, b4, h4, 4))
                                .retryBatch(env.caller, "ParallelCase", "ops-1", 1));
                Future<Map<String, Object>> pU2 = pool.submit(() ->
                        env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, b4, h4, 4))
                                .retryBatch(env.caller, "ParallelCase", "ops-1", 1));
                Future<Map<String, Object>> pL1 = pool.submit(() ->
                        env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, b4, h4, 4))
                                .retryBatch(env.caller, "parallelcase", "ops-1", 1));
                Future<Map<String, Object>> pL2 = pool.submit(() ->
                        env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, b4, h4, 4))
                                .retryBatch(env.caller, "parallelcase", "ops-1", 1));

                Map<String, Object> resPU1 = pU1.get(15, TimeUnit.SECONDS);
                Map<String, Object> resPU2 = pU2.get(15, TimeUnit.SECONDS);
                Map<String, Object> resPL1 = pL1.get(15, TimeUnit.SECONDS);
                Map<String, Object> resPL2 = pL2.get(15, TimeUnit.SECONDS);
                assertThat(resPU1.get("replayRequestId")).isEqualTo("ParallelCase");
                assertThat(resPU1.get("requeued")).isEqualTo("1");
                assertThat(resPU2).isEqualTo(resPU1);
                assertThat(resPL1.get("replayRequestId")).isEqualTo("parallelcase");
                assertThat(resPL1.get("requeued")).isEqualTo("1");
                assertThat(resPL2).isEqualTo(resPL1);
                assertThat(env.consumerRepo.findIndexTask(1L, 8203L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(env.consumerRepo.findIndexTask(1L, 8204L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(countAuditByExactKey(env.jdbc, "ParallelCase")).isEqualTo(1L);
                assertThat(countAuditByExactKey(env.jdbc, "parallelcase")).isEqualTo(1L);

                // Sub-case D: Concurrent single-event retry increments claim_generation strictly 1 -> 2
                String singleEvt = UUID.randomUUID().toString();
                insertInbox(env.jdbc, singleEvt, "8205", "APPLIED", BASE_NOW_MS - 5000);
                insertDeadTask(env.jdbc, 8205L, 1L, "ERR_SINGLE", BASE_NOW_MS - 5000);

                CyclicBarrier bSingle = new CyclicBarrier(2);
                AtomicInteger hSingle = new AtomicInteger();
                Future<Map<String, Object>> s1 = pool.submit(() ->
                        env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, bSingle, hSingle, 2))
                                .retrySingleEvent(env.caller, singleEvt, "SingleCaseRace", "ops-1"));
                Future<Map<String, Object>> s2 = pool.submit(() ->
                        env.newOpsService(new OverlapBarrierOpsRepository(env.jdbc, env.consumerRepo, bSingle, hSingle, 2))
                                .retrySingleEvent(env.caller, singleEvt, "SingleCaseRace", "ops-1"));
                Map<String, Object> rS1 = s1.get(15, TimeUnit.SECONDS);
                Map<String, Object> rS2 = s2.get(15, TimeUnit.SECONDS);
                assertThat(rS1.get("status")).isEqualTo("ACCEPTED");
                assertThat(rS1.get("replayRequestId")).isEqualTo("SingleCaseRace");
                assertThat(rS2).isEqualTo(rS1);
                assertThat(env.consumerRepo.findIndexTask(1L, 8205L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(countAuditByExactKey(env.jdbc, "SingleCaseRace")).isEqualTo(1L);

                // Sub-case E: Concurrent same-key conflicting params return 409 with ZERO extra task mutation
                insertDeadTask(env.jdbc, 8206L, 1L, "ERR_6", BASE_NOW_MS - 4000);
                insertDeadTask(env.jdbc, 8207L, 1L, "ERR_7", BASE_NOW_MS - 3000);

                CountDownLatch winnerReserved = new CountDownLatch(1);
                CountDownLatch loserEntering = new CountDownLatch(1);
                CountDownLatch releaseWinner = new CountDownLatch(1);
                SearchConsumerOpsService conflictWinner = env.newOpsService(new SearchOpsRepository(env.jdbc, env.consumerRepo) {
                    @Override
                    public boolean insertReplayAudit(ReplayAuditRecord record) {
                        boolean ok = super.insertReplayAudit(record);
                        if (ok && "ConflictCaseKey".equals(record.replayRequestId())) {
                            winnerReserved.countDown();
                            try {
                                releaseWinner.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return ok;
                    }
                });
                SearchConsumerOpsService conflictLoser = env.newOpsService(new SearchOpsRepository(env.jdbc, env.consumerRepo) {
                    @Override
                    public boolean insertReplayAudit(ReplayAuditRecord record) {
                        if ("ConflictCaseKey".equals(record.replayRequestId())) {
                            loserEntering.countDown();
                        }
                        return super.insertReplayAudit(record);
                    }
                });
                Future<Map<String, Object>> cwFuture = pool.submit(() ->
                        conflictWinner.retryBatch(env.caller, "ConflictCaseKey", "ops-1", 1));
                assertThat(winnerReserved.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Map<String, Object>> clFuture = pool.submit(() ->
                        conflictLoser.retryBatch(env.caller, "ConflictCaseKey", "ops-2", 2));
                assertThat(loserEntering.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(50);
                releaseWinner.countDown();

                assertThat(cwFuture.get(15, TimeUnit.SECONDS).get("requeued")).isEqualTo("1");
                assertThatThrownBy(() -> clFuture.get(15, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(InternalOpsException.class)
                        .cause()
                        .satisfies(ex -> {
                            InternalOpsException opsEx = (InternalOpsException) ex;
                            assertThat(opsEx.httpStatus()).isEqualTo(409);
                            assertThat(opsEx.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                        });
                assertThat(env.consumerRepo.findIndexTask(1L, 8206L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.PENDING);
                assertThat(env.consumerRepo.findIndexTask(1L, 8206L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(env.consumerRepo.findIndexTask(1L, 8207L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.DEAD);
                assertThat(env.consumerRepo.findIndexTask(1L, 8207L).orElseThrow().claimGeneration()).isEqualTo(1L);
                assertThat(countAuditByExactKey(env.jdbc, "ConflictCaseKey")).isEqualTo(1L);

                // Sub-case F: Transaction failure rollback leaves 0 audit rows and task untouched; same-key retry succeeds
                AtomicBoolean failOnce = new AtomicBoolean(true);
                SearchConsumerOpsService flaky = env.newOpsService(new SearchOpsRepository(env.jdbc, env.consumerRepo) {
                    @Override
                    public void completeReplayAuditInCurrentTx(
                            String replayRequestId, String responseStatus, long requeuedCount, String responseJson) {
                        if ("RollbackCaseKey".equals(replayRequestId) && failOnce.compareAndSet(true, false)) {
                            throw new DataAccessResourceFailureException("Simulated transient DB failure");
                        }
                        super.completeReplayAuditInCurrentTx(replayRequestId, responseStatus, requeuedCount, responseJson);
                    }
                });
                assertThatThrownBy(() -> flaky.retryBatch(env.caller, "RollbackCaseKey", "ops-1", 1))
                        .isInstanceOf(InternalOpsException.class)
                        .satisfies(ex -> assertThat(((InternalOpsException) ex).httpStatus()).isEqualTo(503));
                assertThat(env.consumerRepo.findIndexTask(1L, 8207L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.DEAD);
                assertThat(env.consumerRepo.findIndexTask(1L, 8207L).orElseThrow().claimGeneration()).isEqualTo(1L);
                assertThat(countAuditByExactKey(env.jdbc, "RollbackCaseKey")).isEqualTo(0L);

                Map<String, Object> recovered = flaky.retryBatch(env.caller, "RollbackCaseKey", "ops-1", 1);
                assertThat(recovered.get("status")).isEqualTo("ACCEPTED");
                assertThat(recovered.get("replayRequestId")).isEqualTo("RollbackCaseKey");
                assertThat(recovered.get("requeued")).isEqualTo("1");
                assertThat(env.consumerRepo.findIndexTask(1L, 8207L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.PENDING);
                assertThat(env.consumerRepo.findIndexTask(1L, 8207L).orElseThrow().claimGeneration()).isEqualTo(2L);
                assertThat(countAuditByExactKey(env.jdbc, "RollbackCaseKey")).isEqualTo(1L);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private TestEnv createFreshCaseInsensitiveEnv(boolean useIgnoreCaseUrl) {
        TestEnv env = createBaseEnv(useIgnoreCaseUrl);
        new ResourceDatabasePopulator(new ClassPathResource("db/search-consumer-schema.sql")).execute(env.ds);
        seedGeneration(env.jdbc);
        return env;
    }

    private TestEnv createSearch03R3CaseInsensitiveEnv() {
        TestEnv env = createBaseEnv(false);
        new ResourceDatabasePopulator(new ClassPathResource("db/search-consumer-schema.sql")).execute(env.ds);
        env.jdbc.execute("DROP TABLE search_ops_replay_audit");
        seedGeneration(env.jdbc);
        return env;
    }

    private TestEnv createLegacySearch04CaseInsensitiveEnv(boolean useIgnoreCaseUrl) {
        TestEnv env = createBaseEnv(useIgnoreCaseUrl);
        new ResourceDatabasePopulator(new ClassPathResource("db/search-consumer-schema.sql")).execute(env.ds);
        env.jdbc.execute("DROP TABLE search_ops_replay_audit");
        // Exact SEARCH-04 / SEARCH-04-R1 DDL (VARCHAR(64) PK inheriting default case-insensitive collation)
        env.jdbc.execute("""
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
        env.jdbc.execute("CREATE INDEX idx_search_ops_replay_time ON search_ops_replay_audit (created_at_ms)");
        seedGeneration(env.jdbc);
        return env;
    }

    private TestEnv createBaseEnv(boolean useIgnoreCaseUrl) {
        String dbName = "search_ops_r2_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig config = new HikariConfig();
        String url = "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
                + (useIgnoreCaseUrl ? ";IGNORECASE=TRUE" : "");
        config.setJdbcUrl(url);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(12);
        HikariDataSource ds = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        if (!useIgnoreCaseUrl) {
            jdbc.execute("SET COLLATION ENGLISH STRENGTH PRIMARY");
        }
        return new TestEnv(ds, jdbc);
    }

    private static void seedGeneration(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO search_index_generation (
                    alias_name, active_generation, active_physical_index,
                    require_cold_visibility_guard, cutover_state,
                    active_cache_invalidation_status, active_cache_invalidation_attempts, updated_at_ms
                ) VALUES (?, 1, ?, 0, 'NORMAL', 'DONE', 0, ?)
                """, ALIAS, PHYSICAL_G1, BASE_NOW_MS - 10_000);
    }

    private static Long countAuditByExactKey(JdbcTemplate jdbc, String replayRequestId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_key = ?",
                Long.class,
                (Object) replayRequestId.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static List<String> listAuditIds(JdbcTemplate jdbc) {
        return jdbc.query(
                "SELECT replay_request_id FROM search_ops_replay_audit",
                (rs, rowNum) -> rs.getString("replay_request_id")
        );
    }

    private static void insertInbox(JdbcTemplate jdbc, String eventId, String aggregateId, String status, long ts) {
        jdbc.update("""
                INSERT INTO search_event_inbox (
                    event_id, stream_record_id, event_type, schema_version,
                    aggregate_type, aggregate_id, source_revision, visibility,
                    status, received_at_ms, processed_at_ms
                ) VALUES (?, '1-0', 'ArticlePublished', 1, 'article', ?, 3, 'PUBLIC', ?, ?, ?)
                """, eventId, aggregateId, status, ts, ts);
    }

    private static void insertDeadTask(JdbcTemplate jdbc, long articleId, long claimGen, String err, long ts) {
        jdbc.update("""
                INSERT INTO search_index_task (
                    index_generation, article_id, target_index, target_projection_revision,
                    operation, payload_json, status, attempts, max_attempts, due_at_ms,
                    lease_until_ms, claim_generation, last_error, es_applied_revision,
                    cache_invalidation_status, cache_invalidation_attempts, updated_at_ms
                ) VALUES (1, ?, ?, 3, 'UPSERT', '{"id":8000}', 'DEAD', 5, 5, ?, NULL, ?, ?, 0, 'NONE', 0, ?)
                """, articleId, PHYSICAL_G1, ts, claimGen, err, ts);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) throws BeansException { return instance; }
            @Override
            public T getIfAvailable() throws BeansException { return instance; }
            @Override
            public T getIfUnique() throws BeansException { return instance; }
            @Override
            public T getObject() throws BeansException { return instance; }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return providerOf(null);
    }

    private static final class TestEnv implements AutoCloseable {
        final HikariDataSource ds;
        final JdbcTemplate jdbc;
        final ObjectMapper mapper;
        final SearchConsumerRepository consumerRepo;
        final TransactionTemplate tx;
        final SearchElasticsearchProperties esProps;
        final SearchOpsProperties opsProps;
        final SearchMetrics metrics;
        final Clock clock;
        final ServiceTokenVerifier.Principal caller;

        TestEnv(HikariDataSource ds, JdbcTemplate jdbc) {
            this.ds = ds;
            this.jdbc = jdbc;
            this.mapper = new ObjectMapper();
            this.consumerRepo = new SearchConsumerRepository(jdbc, mapper);
            this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            this.esProps = new SearchElasticsearchProperties();
            this.esProps.setIndex(ALIAS);
            this.opsProps = new SearchOpsProperties();
            this.metrics = new SearchMetrics(new SimpleMeterRegistry());
            this.clock = Clock.fixed(Instant.ofEpochMilli(BASE_NOW_MS), ZoneOffset.UTC);
            this.caller = new ServiceTokenVerifier.Principal(
                    "knode-content", "knode-search", Set.of("ops:read", "ops:retry"), BASE_NOW_MS / 1000 + 300);
        }

        SearchConsumerOpsService newOpsService(SearchOpsRepository repo) {
            return new SearchConsumerOpsService(
                    providerOf(repo),
                    providerOf(consumerRepo),
                    providerOf(tx),
                    emptyProvider(),
                    opsProps,
                    esProps,
                    metrics,
                    mapper,
                    clock
            );
        }

        @Override
        public void close() {
            ds.close();
        }
    }

    private static final class OverlapBarrierOpsRepository extends SearchOpsRepository {
        private final CyclicBarrier barrier;
        private final AtomicInteger emptyHits;
        private final int parties;

        OverlapBarrierOpsRepository(
                JdbcTemplate jdbcTemplate,
                SearchConsumerRepository consumerRepository,
                CyclicBarrier barrier,
                AtomicInteger emptyHits,
                int parties) {
            super(jdbcTemplate, consumerRepository);
            this.barrier = barrier;
            this.emptyHits = emptyHits;
            this.parties = parties;
        }

        @Override
        public Optional<ReplayAuditRecord> findReplayAudit(String replayRequestId) {
            Optional<ReplayAuditRecord> result = super.findReplayAudit(replayRequestId);
            if (result.isEmpty() && emptyHits.incrementAndGet() <= parties) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException("Barrier failed", ex);
                }
            }
            return result;
        }
    }
}
