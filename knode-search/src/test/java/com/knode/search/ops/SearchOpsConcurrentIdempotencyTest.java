package com.knode.search.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.persistence.SearchConsumerRepository;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import com.knode.search.consumer.persistence.SearchIndexTaskStatus;
import com.knode.search.metrics.SearchMetrics;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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

class SearchOpsConcurrentIdempotencyTest {
    private static final String ALIAS = "knode_it_r1_articles_search";
    private static final String PHYSICAL_G1 = ALIAS + "_g1";
    private static final long BASE_NOW_MS = 1_760_000_000_000L;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private SearchConsumerRepository consumerRepository;
    private ObjectMapper objectMapper;
    private SearchMetrics searchMetrics;
    private SearchElasticsearchProperties esProperties;
    private SearchOpsProperties opsProperties;
    private Clock fixedClock;
    private ServiceTokenVerifier.Principal caller;

    @BeforeEach
    void setUp() {
        String dbName = "search_ops_r1_" + UUID.randomUUID().toString().replace("-", "");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(12);
        dataSource = new HikariDataSource(config);

        new ResourceDatabasePopulator(new ClassPathResource("db/search-consumer-schema.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(txManager);
        objectMapper = new ObjectMapper();
        consumerRepository = new SearchConsumerRepository(jdbcTemplate, objectMapper);
        searchMetrics = new SearchMetrics(new SimpleMeterRegistry());
        esProperties = new SearchElasticsearchProperties();
        esProperties.setIndex(ALIAS);
        opsProperties = new SearchOpsProperties();
        fixedClock = Clock.fixed(Instant.ofEpochMilli(BASE_NOW_MS), ZoneOffset.UTC);
        caller = new ServiceTokenVerifier.Principal(
                "knode-content", "knode-search", Set.of("ops:read", "ops:retry"), BASE_NOW_MS / 1000 + 300);

        jdbcTemplate.update("""
                INSERT INTO search_index_generation (
                    alias_name, active_generation, active_physical_index,
                    require_cold_visibility_guard, cutover_state,
                    active_cache_invalidation_status, active_cache_invalidation_attempts, updated_at_ms
                ) VALUES (?, 1, ?, 0, 'NORMAL', 'DONE', 0, ?)
                """, ALIAS, PHYSICAL_G1, BASE_NOW_MS - 10_000);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void concurrentBatchRetryWithSameReplayRequestIdLimitOneRequeuesExactlyOneTask() throws Exception {
        String evt1 = UUID.randomUUID().toString();
        String evt2 = UUID.randomUUID().toString();
        insertInbox(evt1, "7001", "APPLIED", BASE_NOW_MS - 5000);
        insertDeadTask(7001L, 1L, "ES_TIMEOUT_1", BASE_NOW_MS - 5000);
        insertInbox(evt2, "7002", "APPLIED", BASE_NOW_MS - 4000);
        insertDeadTask(7002L, 1L, "ES_TIMEOUT_2", BASE_NOW_MS - 4000);

        CyclicBarrier overlapBarrier = new CyclicBarrier(2);
        AtomicInteger emptyAuditHits = new AtomicInteger();
        SearchOpsRepository repoA = new OverlapBarrierOpsRepository(jdbcTemplate, consumerRepository, overlapBarrier, emptyAuditHits, 2);
        SearchOpsRepository repoB = new OverlapBarrierOpsRepository(jdbcTemplate, consumerRepository, overlapBarrier, emptyAuditHits, 2);

        SearchConsumerOpsService instanceA = newOpsService(repoA);
        SearchConsumerOpsService instanceB = newOpsService(repoB);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> fA = pool.submit(() -> instanceA.retryBatch(caller, "replay-batch-r1", "ops-1", 1));
            Future<Map<String, Object>> fB = pool.submit(() -> instanceB.retryBatch(caller, "replay-batch-r1", "ops-1", 1));

            Map<String, Object> respA = fA.get(15, TimeUnit.SECONDS);
            Map<String, Object> respB = fB.get(15, TimeUnit.SECONDS);

            assertThat(respA.get("status")).isEqualTo("ACCEPTED");
            assertThat(respA.get("requeued")).isEqualTo("1");
            assertThat(respB).isEqualTo(respA);

            SearchIndexTaskRecord t1 = consumerRepository.findIndexTask(1L, 7001L).orElseThrow();
            SearchIndexTaskRecord t2 = consumerRepository.findIndexTask(1L, 7002L).orElseThrow();
            Long auditCount = countAuditRows("replay-batch-r1");

            assertThat(auditCount).isEqualTo(1L);
            assertThat(List.of(t1.status(), t2.status()))
                    .containsExactlyInAnyOrder(SearchIndexTaskStatus.PENDING, SearchIndexTaskStatus.DEAD);
            assertThat(t1.claimGeneration() + t2.claimGeneration()).isEqualTo(3L);
            assertThat(t1.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(t1.claimGeneration()).isEqualTo(2L);
            assertThat(t1.attempts()).isEqualTo(0);
            assertThat(t2.status()).isEqualTo(SearchIndexTaskStatus.DEAD);
            assertThat(t2.claimGeneration()).isEqualTo(1L);
            assertThat(t2.attempts()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSingleRetryWithSameReplayRequestIdIncrementsClaimGenerationOnce() throws Exception {
        String evt = UUID.randomUUID().toString();
        insertInbox(evt, "7101", "APPLIED", BASE_NOW_MS - 5000);
        insertDeadTask(7101L, 1L, "ES_CONNECTION_RESET", BASE_NOW_MS - 5000);

        CyclicBarrier overlapBarrier = new CyclicBarrier(2);
        AtomicInteger emptyAuditHits = new AtomicInteger();
        SearchOpsRepository repoA = new OverlapBarrierOpsRepository(jdbcTemplate, consumerRepository, overlapBarrier, emptyAuditHits, 2);
        SearchOpsRepository repoB = new OverlapBarrierOpsRepository(jdbcTemplate, consumerRepository, overlapBarrier, emptyAuditHits, 2);

        SearchConsumerOpsService instanceA = newOpsService(repoA);
        SearchConsumerOpsService instanceB = newOpsService(repoB);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> fA = pool.submit(() -> instanceA.retrySingleEvent(caller, evt, "replay-single-r1", "ops-1"));
            Future<Map<String, Object>> fB = pool.submit(() -> instanceB.retrySingleEvent(caller, evt, "replay-single-r1", "ops-1"));

            Map<String, Object> respA = fA.get(15, TimeUnit.SECONDS);
            Map<String, Object> respB = fB.get(15, TimeUnit.SECONDS);

            assertThat(respA.get("status")).isEqualTo("ACCEPTED");
            assertThat(respA.get("requeued")).isEqualTo("1");
            assertThat(respA.get("reason")).isEqualTo("DEAD_TASK_REQUEUED");
            assertThat(respB).isEqualTo(respA);

            SearchIndexTaskRecord task = consumerRepository.findIndexTask(1L, 7101L).orElseThrow();
            Long auditCount = countAuditRows("replay-single-r1");

            assertThat(auditCount).isEqualTo(1L);
            assertThat(task.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(task.attempts()).isEqualTo(0);
            assertThat(task.claimGeneration()).isEqualTo(2L);

            // A separate replayRequestId against the already-ready PENDING task returns NOOP without bumping claim_generation
            SearchConsumerOpsService plainInstance = newOpsService(new SearchOpsRepository(jdbcTemplate, consumerRepository));
            Map<String, Object> secondKeyResp = plainInstance.retrySingleEvent(caller, evt, "replay-single-r1-second", "ops-1");
            assertThat(secondKeyResp.get("status")).isEqualTo("NOOP");
            assertThat(secondKeyResp.get("requeued")).isEqualTo("0");
            assertThat(consumerRepository.findIndexTask(1L, 7101L).orElseThrow().claimGeneration()).isEqualTo(2L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentConflictingParametersAndActiveLeaseProtectionLeaveTasksUnmutated() throws Exception {
        // Sub-case A: Concurrent batch requests with SAME replayRequestId but DIFFERENT limit & operator
        String evt1 = UUID.randomUUID().toString();
        String evt2 = UUID.randomUUID().toString();
        insertInbox(evt1, "7201", "APPLIED", BASE_NOW_MS - 6000);
        insertDeadTask(7201L, 1L, "ERR_1", BASE_NOW_MS - 6000);
        insertInbox(evt2, "7202", "APPLIED", BASE_NOW_MS - 5000);
        insertDeadTask(7202L, 1L, "ERR_2", BASE_NOW_MS - 5000);

        CountDownLatch winnerReserved = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        CountDownLatch loserEnteringInsert = new CountDownLatch(1);

        SearchOpsRepository winnerRepo = new SearchOpsRepository(jdbcTemplate, consumerRepository) {
            @Override
            public boolean insertReplayAudit(ReplayAuditRecord record) {
                boolean ok = super.insertReplayAudit(record);
                if (ok && "replay-conflict-batch".equals(record.replayRequestId())) {
                    winnerReserved.countDown();
                    try {
                        releaseWinner.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                return ok;
            }
        };
        SearchOpsRepository loserRepo = new SearchOpsRepository(jdbcTemplate, consumerRepository) {
            @Override
            public Optional<ReplayAuditRecord> findReplayAudit(String replayRequestId) {
                // Ensure loser passes initial read while winner's tx is uncommitted (sees empty)
                if ("replay-conflict-batch".equals(replayRequestId)) {
                    try {
                        winnerReserved.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.findReplayAudit(replayRequestId);
            }

            @Override
            public boolean insertReplayAudit(ReplayAuditRecord record) {
                if ("replay-conflict-batch".equals(record.replayRequestId())) {
                    loserEnteringInsert.countDown();
                }
                return super.insertReplayAudit(record);
            }
        };

        SearchConsumerOpsService winnerService = newOpsService(winnerRepo);
        SearchConsumerOpsService loserService = newOpsService(loserRepo);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<Map<String, Object>> winnerFuture =
                    pool.submit(() -> winnerService.retryBatch(caller, "replay-conflict-batch", "op-winner", 1));
            Future<Map<String, Object>> loserFuture =
                    pool.submit(() -> loserService.retryBatch(caller, "replay-conflict-batch", "op-conflict", 2));

            assertThat(loserEnteringInsert.await(5, TimeUnit.SECONDS)).isTrue();
            // Give loser a brief moment to block on DB PK lock before releasing winner to commit
            Thread.sleep(50);
            releaseWinner.countDown();

            Map<String, Object> winnerResp = winnerFuture.get(15, TimeUnit.SECONDS);
            assertThat(winnerResp.get("status")).isEqualTo("ACCEPTED");
            assertThat(winnerResp.get("requeued")).isEqualTo("1");
            assertThat(winnerResp.get("operator")).isEqualTo("op-winner");

            assertThatThrownBy(() -> loserFuture.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InternalOpsException.class)
                    .cause()
                    .satisfies(ex -> {
                        InternalOpsException opsEx = (InternalOpsException) ex;
                        assertThat(opsEx.httpStatus()).isEqualTo(409);
                        assertThat(opsEx.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    });

            // Only 7201 was mutated by winner; conflicting request made ZERO extra task updates
            SearchIndexTaskRecord t7201 = consumerRepository.findIndexTask(1L, 7201L).orElseThrow();
            SearchIndexTaskRecord t7202 = consumerRepository.findIndexTask(1L, 7202L).orElseThrow();
            assertThat(t7201.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(t7201.claimGeneration()).isEqualTo(2L);
            assertThat(t7202.status()).isEqualTo(SearchIndexTaskStatus.DEAD);
            assertThat(t7202.claimGeneration()).isEqualTo(1L);
            assertThat(countAuditRows("replay-conflict-batch")).isEqualTo(1L);

            // Sub-case B: Cross-endpoint (single vs batch) conflict with SAME replayRequestId
            String evt3 = UUID.randomUUID().toString();
            insertInbox(evt3, "7203", "APPLIED", BASE_NOW_MS - 4000);
            insertDeadTask(7203L, 1L, "ERR_3", BASE_NOW_MS - 4000);

            CountDownLatch mixWinnerReserved = new CountDownLatch(1);
            CountDownLatch mixReleaseWinner = new CountDownLatch(1);
            CountDownLatch mixLoserReady = new CountDownLatch(1);
            SearchConsumerOpsService mixWinner = newOpsService(new SearchOpsRepository(jdbcTemplate, consumerRepository) {
                @Override
                public boolean insertReplayAudit(ReplayAuditRecord record) {
                    boolean ok = super.insertReplayAudit(record);
                    if (ok && "replay-conflict-mix".equals(record.replayRequestId())) {
                        mixWinnerReserved.countDown();
                        try {
                            mixReleaseWinner.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return ok;
                }
            });
            SearchConsumerOpsService mixLoser = newOpsService(new SearchOpsRepository(jdbcTemplate, consumerRepository) {
                @Override
                public boolean insertReplayAudit(ReplayAuditRecord record) {
                    if ("replay-conflict-mix".equals(record.replayRequestId())) {
                        mixLoserReady.countDown();
                    }
                    return super.insertReplayAudit(record);
                }
            });

            Future<Map<String, Object>> mixSingleFuture =
                    pool.submit(() -> mixWinner.retrySingleEvent(caller, evt3, "replay-conflict-mix", "op-s"));
            assertThat(mixWinnerReserved.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Map<String, Object>> mixBatchFuture =
                    pool.submit(() -> mixLoser.retryBatch(caller, "replay-conflict-mix", "op-b", 10));
            assertThat(mixLoserReady.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            mixReleaseWinner.countDown();

            assertThat(mixSingleFuture.get(15, TimeUnit.SECONDS).get("status")).isEqualTo("ACCEPTED");
            assertThatThrownBy(() -> mixBatchFuture.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InternalOpsException.class)
                    .cause()
                    .satisfies(ex -> assertThat(((InternalOpsException) ex).httpStatus()).isEqualTo(409));

            // 7203 updated by single retry; 7202 STILL untouched DEAD despite conflicting batch limit=10!
            assertThat(consumerRepository.findIndexTask(1L, 7203L).orElseThrow().status())
                    .isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(consumerRepository.findIndexTask(1L, 7203L).orElseThrow().claimGeneration()).isEqualTo(2L);
            assertThat(consumerRepository.findIndexTask(1L, 7202L).orElseThrow().status())
                    .isEqualTo(SearchIndexTaskStatus.DEAD);
            assertThat(consumerRepository.findIndexTask(1L, 7202L).orElseThrow().claimGeneration()).isEqualTo(1L);
            assertThat(countAuditRows("replay-conflict-mix")).isEqualTo(1L);

            // Sub-case C: Concurrent active-lease single retries both reject with 409 and leak ZERO audit rows
            String evtActive = UUID.randomUUID().toString();
            insertInbox(evtActive, "7205", "APPLIED", BASE_NOW_MS - 3000);
            insertActiveLeasedTask(7205L, 4L, BASE_NOW_MS + 60_000L, BASE_NOW_MS - 3000);

            CyclicBarrier leaseBarrier = new CyclicBarrier(2);
            AtomicInteger leaseHits = new AtomicInteger();
            SearchConsumerOpsService leaseA = newOpsService(
                    new OverlapBarrierOpsRepository(jdbcTemplate, consumerRepository, leaseBarrier, leaseHits, 2));
            SearchConsumerOpsService leaseB = newOpsService(
                    new OverlapBarrierOpsRepository(jdbcTemplate, consumerRepository, leaseBarrier, leaseHits, 2));

            Future<Map<String, Object>> lfA = pool.submit(() -> leaseA.retrySingleEvent(caller, evtActive, "replay-active-lease", "ops-1"));
            Future<Map<String, Object>> lfB = pool.submit(() -> leaseB.retrySingleEvent(caller, evtActive, "replay-active-lease", "ops-1"));

            for (Future<Map<String, Object>> lf : List.of(lfA, lfB)) {
                assertThatThrownBy(() -> lf.get(15, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(InternalOpsException.class)
                        .cause()
                        .satisfies(ex -> {
                            InternalOpsException opsEx = (InternalOpsException) ex;
                            assertThat(opsEx.httpStatus()).isEqualTo(409);
                            assertThat(opsEx.errorCode()).isEqualTo("STATE_CONFLICT");
                        });
            }
            SearchIndexTaskRecord activeTask = consumerRepository.findIndexTask(1L, 7205L).orElseThrow();
            assertThat(activeTask.status()).isEqualTo(SearchIndexTaskStatus.LEASED);
            assertThat(activeTask.claimGeneration()).isEqualTo(4L);
            assertThat(activeTask.leaseUntilMs()).isEqualTo(BASE_NOW_MS + 60_000L);
            assertThat(countAuditRows("replay-active-lease")).isEqualTo(0L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedTransactionRollsBackBothTaskAndAuditAllowingSubsequentAndRacingRetry() throws Exception {
        // Sub-case A: Sequential recovery after mid-tx DB failure during audit finalization
        String evt1 = UUID.randomUUID().toString();
        String evt2 = UUID.randomUUID().toString();
        insertInbox(evt1, "7301", "APPLIED", BASE_NOW_MS - 6000);
        insertDeadTask(7301L, 1L, "ERR_1", BASE_NOW_MS - 6000);
        insertInbox(evt2, "7302", "APPLIED", BASE_NOW_MS - 5000);
        insertDeadTask(7302L, 1L, "ERR_2", BASE_NOW_MS - 5000);

        AtomicBoolean failAuditFinalize = new AtomicBoolean(true);
        SearchOpsRepository flakyRepo = new SearchOpsRepository(jdbcTemplate, consumerRepository) {
            @Override
            public void completeReplayAuditInCurrentTx(
                    String replayRequestId, String responseStatus, long requeuedCount, String responseJson) {
                if (failAuditFinalize.compareAndSet(true, false)) {
                    throw new DataAccessResourceFailureException("Simulated transient DB failure before commit");
                }
                super.completeReplayAuditInCurrentTx(replayRequestId, responseStatus, requeuedCount, responseJson);
            }
        };
        SearchConsumerOpsService flakyService = newOpsService(flakyRepo);

        assertThatThrownBy(() -> flakyService.retryBatch(caller, "replay-tx-fail-seq", "ops-1", 1))
                .isInstanceOf(InternalOpsException.class)
                .satisfies(ex -> {
                    InternalOpsException opsEx = (InternalOpsException) ex;
                    assertThat(opsEx.httpStatus()).isEqualTo(503);
                    assertThat(opsEx.errorCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                });

        // Rollback verification: ZERO task updates AND ZERO half-baked audit rows left behind
        assertThat(consumerRepository.findIndexTask(1L, 7301L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.DEAD);
        assertThat(consumerRepository.findIndexTask(1L, 7301L).orElseThrow().claimGeneration()).isEqualTo(1L);
        assertThat(consumerRepository.findIndexTask(1L, 7302L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.DEAD);
        assertThat(consumerRepository.findIndexTask(1L, 7302L).orElseThrow().claimGeneration()).isEqualTo(1L);
        assertThat(countAuditRows("replay-tx-fail-seq")).isEqualTo(0L);

        // Retrying with the EXACT SAME replayRequestId after failure succeeds cleanly
        Map<String, Object> recovered = flakyService.retryBatch(caller, "replay-tx-fail-seq", "ops-1", 1);
        assertThat(recovered.get("status")).isEqualTo("ACCEPTED");
        assertThat(recovered.get("requeued")).isEqualTo("1");
        assertThat(consumerRepository.findIndexTask(1L, 7301L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.PENDING);
        assertThat(consumerRepository.findIndexTask(1L, 7301L).orElseThrow().claimGeneration()).isEqualTo(2L);
        assertThat(consumerRepository.findIndexTask(1L, 7302L).orElseThrow().status()).isEqualTo(SearchIndexTaskStatus.DEAD);
        assertThat(consumerRepository.findIndexTask(1L, 7302L).orElseThrow().claimGeneration()).isEqualTo(1L);
        assertThat(countAuditRows("replay-tx-fail-seq")).isEqualTo(1L);

        // Sub-case B: Concurrent race where Instance A reserves PK, fails & rolls back while Instance B is racing on the same key
        String evt3 = UUID.randomUUID().toString();
        insertInbox(evt3, "7303", "APPLIED", BASE_NOW_MS - 4000);
        insertDeadTask(7303L, 1L, "ERR_3", BASE_NOW_MS - 4000);

        CountDownLatch failingWinnerReserved = new CountDownLatch(1);
        CountDownLatch waiterReadyToInsert = new CountDownLatch(1);
        CountDownLatch releaseFailingWinner = new CountDownLatch(1);

        SearchConsumerOpsService failingInstance = newOpsService(new SearchOpsRepository(jdbcTemplate, consumerRepository) {
            @Override
            public boolean insertReplayAudit(ReplayAuditRecord record) {
                boolean ok = super.insertReplayAudit(record);
                if (ok && "replay-tx-fail-race".equals(record.replayRequestId())) {
                    failingWinnerReserved.countDown();
                }
                return ok;
            }

            @Override
            public void completeReplayAuditInCurrentTx(
                    String replayRequestId, String responseStatus, long requeuedCount, String responseJson) {
                if ("replay-tx-fail-race".equals(replayRequestId)) {
                    try {
                        releaseFailingWinner.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    throw new DataAccessResourceFailureException("Simulated crash before commit");
                }
                super.completeReplayAuditInCurrentTx(replayRequestId, responseStatus, requeuedCount, responseJson);
            }
        });
        SearchConsumerOpsService waiterInstance = newOpsService(new SearchOpsRepository(jdbcTemplate, consumerRepository) {
            @Override
            public boolean insertReplayAudit(ReplayAuditRecord record) {
                if ("replay-tx-fail-race".equals(record.replayRequestId())) {
                    waiterReadyToInsert.countDown();
                }
                return super.insertReplayAudit(record);
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> failingFuture =
                    pool.submit(() -> failingInstance.retrySingleEvent(caller, evt3, "replay-tx-fail-race", "ops-1"));
            assertThat(failingWinnerReserved.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Map<String, Object>> waiterFuture =
                    pool.submit(() -> waiterInstance.retrySingleEvent(caller, evt3, "replay-tx-fail-race", "ops-1"));
            assertThat(waiterReadyToInsert.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            releaseFailingWinner.countDown();

            assertThatThrownBy(() -> failingFuture.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InternalOpsException.class)
                    .cause()
                    .satisfies(ex -> assertThat(((InternalOpsException) ex).httpStatus()).isEqualTo(503));

            Map<String, Object> waiterResp = waiterFuture.get(15, TimeUnit.SECONDS);
            assertThat(waiterResp.get("status")).isEqualTo("ACCEPTED");
            assertThat(waiterResp.get("requeued")).isEqualTo("1");

            SearchIndexTaskRecord t7303 = consumerRepository.findIndexTask(1L, 7303L).orElseThrow();
            assertThat(t7303.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(t7303.claimGeneration()).isEqualTo(2L);
            assertThat(countAuditRows("replay-tx-fail-race")).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fourWayConcurrentBatchAndSingleRetriesOverSharedStoragePreserveExactSingleExecution() throws Exception {
        // 4 concurrent instances racing on batch retry (limit=1, 4 DEAD tasks)
        for (int i = 0; i < 4; i++) {
            long id = 7401L + i;
            insertInbox(UUID.randomUUID().toString(), Long.toString(id), "APPLIED", BASE_NOW_MS - 8000 + i * 100L);
            insertDeadTask(id, 1L, "ERR_4WAY_" + i, BASE_NOW_MS - 8000 + i * 100L);
        }

        CyclicBarrier batchBarrier = new CyclicBarrier(4);
        AtomicInteger batchHits = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Map<String, Object>>> batchFutures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                SearchConsumerOpsService svc = newOpsService(
                        new OverlapBarrierOpsRepository(jdbcTemplate, consumerRepository, batchBarrier, batchHits, 4));
                batchFutures.add(pool.submit(() -> svc.retryBatch(caller, "replay-4way-batch", "ops-4way", 1)));
            }
            Map<String, Object> firstResp = batchFutures.get(0).get(15, TimeUnit.SECONDS);
            assertThat(firstResp.get("status")).isEqualTo("ACCEPTED");
            assertThat(firstResp.get("requeued")).isEqualTo("1");
            for (int i = 1; i < 4; i++) {
                assertThat(batchFutures.get(i).get(15, TimeUnit.SECONDS)).isEqualTo(firstResp);
            }

            int pendingCount = 0;
            int deadCount = 0;
            long claimGenSum = 0;
            for (int i = 0; i < 4; i++) {
                SearchIndexTaskRecord rec = consumerRepository.findIndexTask(1L, 7401L + i).orElseThrow();
                if (rec.status() == SearchIndexTaskStatus.PENDING) {
                    pendingCount++;
                } else if (rec.status() == SearchIndexTaskStatus.DEAD) {
                    deadCount++;
                }
                claimGenSum += rec.claimGeneration();
            }
            assertThat(pendingCount).isEqualTo(1);
            assertThat(deadCount).isEqualTo(3);
            assertThat(claimGenSum).isEqualTo(5L); // 2 + 1 + 1 + 1
            assertThat(countAuditRows("replay-4way-batch")).isEqualTo(1L);

            // 4 concurrent instances racing on single retry (1 DEAD task, claim_generation=1)
            String singleEvt = UUID.randomUUID().toString();
            insertInbox(singleEvt, "7410", "APPLIED", BASE_NOW_MS - 2000);
            insertDeadTask(7410L, 1L, "ERR_4WAY_SINGLE", BASE_NOW_MS - 2000);

            CyclicBarrier singleBarrier = new CyclicBarrier(4);
            AtomicInteger singleHits = new AtomicInteger();
            List<Future<Map<String, Object>>> singleFutures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                SearchConsumerOpsService svc = newOpsService(
                        new OverlapBarrierOpsRepository(jdbcTemplate, consumerRepository, singleBarrier, singleHits, 4));
                singleFutures.add(pool.submit(() -> svc.retrySingleEvent(caller, singleEvt, "replay-4way-single", "ops-4way")));
            }
            Map<String, Object> firstSingle = singleFutures.get(0).get(15, TimeUnit.SECONDS);
            assertThat(firstSingle.get("status")).isEqualTo("ACCEPTED");
            assertThat(firstSingle.get("requeued")).isEqualTo("1");
            for (int i = 1; i < 4; i++) {
                assertThat(singleFutures.get(i).get(15, TimeUnit.SECONDS)).isEqualTo(firstSingle);
            }

            SearchIndexTaskRecord t7410 = consumerRepository.findIndexTask(1L, 7410L).orElseThrow();
            assertThat(t7410.status()).isEqualTo(SearchIndexTaskStatus.PENDING);
            assertThat(t7410.claimGeneration()).isEqualTo(2L);
            assertThat(countAuditRows("replay-4way-single")).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    private SearchConsumerOpsService newOpsService(SearchOpsRepository repo) {
        return new SearchConsumerOpsService(
                providerOf(repo),
                providerOf(consumerRepository),
                providerOf(transactionTemplate),
                emptyProvider(),
                opsProperties,
                esProperties,
                searchMetrics,
                objectMapper,
                fixedClock
        );
    }

    private Long countAuditRows(String replayRequestId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_ops_replay_audit WHERE replay_request_id = ?",
                Long.class,
                replayRequestId
        );
    }

    private void insertInbox(String eventId, String aggregateId, String status, long ts) {
        jdbcTemplate.update("""
                INSERT INTO search_event_inbox (
                    event_id, event_type, schema_version, aggregate_type, aggregate_id, source_revision,
                    stream_record_id, status, received_at_ms, processed_at_ms
                ) VALUES (?, 'ArticlePublished', 1, 'ARTICLE', ?, 3, '1-0', ?, ?, ?)
                """, eventId, aggregateId, status, ts, ts);
    }

    private void insertDeadTask(long articleId, long claimGen, String lastError, long ts) {
        jdbcTemplate.update("""
                INSERT INTO search_index_task (
                    index_generation, target_index, article_id, target_projection_revision, operation,
                    payload_json, status, attempts, max_attempts, due_at_ms, lease_until_ms,
                    claim_generation, last_error, es_applied_revision,
                    cache_invalidation_status, cache_invalidation_attempts, updated_at_ms
                ) VALUES (1, ?, ?, 1, 'UPSERT', '{"id":1,"searchable":true}', 'DEAD', 5, 5, ?, NULL, ?, ?, 0, 'NONE', 0, ?)
                """, PHYSICAL_G1, articleId, ts, claimGen, lastError, ts);
    }

    private void insertActiveLeasedTask(long articleId, long claimGen, long leaseUntilMs, long ts) {
        jdbcTemplate.update("""
                INSERT INTO search_index_task (
                    index_generation, target_index, article_id, target_projection_revision, operation,
                    payload_json, status, attempts, max_attempts, due_at_ms, lease_until_ms,
                    claim_generation, last_error, es_applied_revision,
                    cache_invalidation_status, cache_invalidation_attempts, updated_at_ms
                ) VALUES (1, ?, ?, 1, 'UPSERT', '{"id":1,"searchable":true}', 'LEASED', 1, 5, ?, ?, ?, NULL, 0, 'NONE', 0, ?)
                """, PHYSICAL_G1, articleId, ts, leaseUntilMs, claimGen, ts);
    }

    private static <T> ObjectProvider<T> providerOf(T instance) {
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

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) throws BeansException {
                return null;
            }

            @Override
            public T getIfAvailable() throws BeansException {
                return null;
            }

            @Override
            public T getIfUnique() throws BeansException {
                return null;
            }

            @Override
            public T getObject() throws BeansException {
                return null;
            }
        };
    }

    private static class OverlapBarrierOpsRepository extends SearchOpsRepository {
        private final CyclicBarrier barrier;
        private final AtomicInteger hitCounter;
        private final int maxBarrierHits;

        OverlapBarrierOpsRepository(
                JdbcTemplate jdbcTemplate,
                SearchConsumerRepository consumerRepository,
                CyclicBarrier barrier,
                AtomicInteger hitCounter,
                int maxBarrierHits) {
            super(jdbcTemplate, consumerRepository);
            this.barrier = barrier;
            this.hitCounter = hitCounter;
            this.maxBarrierHits = maxBarrierHits;
        }

        @Override
        public Optional<ReplayAuditRecord> findReplayAudit(String replayRequestId) {
            Optional<ReplayAuditRecord> res = super.findReplayAudit(replayRequestId);
            if (res.isEmpty() && hitCounter.incrementAndGet() <= maxBarrierHits) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new RuntimeException("Barrier wait failed", ex);
                }
            }
            return res;
        }
    }
}
