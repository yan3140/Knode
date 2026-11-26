package com.knode.search.ops;

import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.stream.ContentStreamClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(prefix = "knode.search.consumer", name = "enabled", havingValue = "true")
public class SearchBacklogMetricsBinder {
    private static final long SNAPSHOT_TTL_MS = 2000L;

    private final SearchOpsRepository opsRepository;
    private final ContentStreamClient streamClient;
    private final String aliasName;
    private final Clock clock;
    private final AtomicReference<CachedMetrics> cache = new AtomicReference<>(CachedMetrics.UNAVAILABLE);

    public SearchBacklogMetricsBinder(
            MeterRegistry registry,
            SearchOpsRepository opsRepository,
            ObjectProvider<ContentStreamClient> streamClientProvider,
            SearchElasticsearchProperties esProperties,
            Clock clock) {
        this.opsRepository = opsRepository;
        this.streamClient = streamClientProvider.getIfAvailable();
        this.aliasName = esProperties != null && StringUtils.hasText(esProperties.getIndex())
                ? esProperties.getIndex().trim()
                : "knode_local_articles_search";
        this.clock = clock;

        Gauge.builder("knode.search.index.tasks.pending", this, b -> b.current().pendingTasks())
                .description("Pending search index tasks in active generation (-1 when DB unavailable)")
                .register(registry);
        Gauge.builder("knode.search.index.tasks.leased", this, b -> b.current().leasedTasks())
                .description("Leased search index tasks in active generation (-1 when DB unavailable)")
                .register(registry);
        Gauge.builder("knode.search.index.tasks.expired_leased", this, b -> b.current().expiredLeasedTasks())
                .description("Expired leased search index tasks in active generation (-1 when DB unavailable)")
                .register(registry);
        Gauge.builder("knode.search.index.tasks.dead", this, b -> b.current().deadTasks())
                .description("Dead search index tasks in active generation (-1 when DB unavailable)")
                .register(registry);
        Gauge.builder("knode.search.inbox.protocol_failed", this, b -> b.current().protocolFailed())
                .description("Archived malformed protocol messages (-1 when DB unavailable)")
                .register(registry);
        Gauge.builder("knode.search.cache.compensation.pending", this, b -> b.current().pendingCacheCompensations())
                .description("Pending cache invalidation compensations across tasks and generation (-1 when DB unavailable)")
                .register(registry);
        Gauge.builder("knode.search.rebuild.active", this, b -> b.current().rebuildActive())
                .description("Whether an active rebuild job is running for the alias (1 active, 0 idle, -1 unavailable)")
                .register(registry);
        Gauge.builder("knode.search.stream.pending", this, b -> b.current().streamPending())
                .description("Pending entries in Redis Stream consumer group (-1 when unconfigured or unavailable)")
                .register(registry);
    }

    public CachedMetrics refreshNow() {
        long nowMs = clock.millis();
        CachedMetrics fresh = loadFresh(nowMs);
        cache.set(fresh);
        return fresh;
    }

    private CachedMetrics current() {
        long nowMs = clock.millis();
        CachedMetrics existing = cache.get();
        if (existing != CachedMetrics.UNAVAILABLE && (nowMs - existing.capturedAtMs()) < SNAPSHOT_TTL_MS) {
            return existing;
        }
        CachedMetrics fresh = loadFresh(nowMs);
        cache.set(fresh);
        return fresh;
    }

    private CachedMetrics loadFresh(long nowMs) {
        long pending = -1L;
        long leased = -1L;
        long expiredLeased = -1L;
        long dead = -1L;
        long protocolFailed = -1L;
        long cacheCompPending = -1L;
        long rebuildActive = -1L;
        try {
            SearchOpsRepository.OpsSummaryDbSnapshot db =
                    opsRepository.loadSummarySnapshot(aliasName, aliasName, nowMs);
            pending = db.taskCounts().getOrDefault("PENDING", 0L);
            leased = db.taskCounts().getOrDefault("LEASED", 0L);
            expiredLeased = db.expiredLeased();
            dead = db.taskCounts().getOrDefault("DEAD", 0L);
            protocolFailed = db.inboxCounts().getOrDefault("PROTOCOL_FAILED", 0L);
            long genPending = "PENDING".equals(db.generationCacheStatus()) ? 1L : 0L;
            cacheCompPending = db.taskCachePending() + genPending;
            rebuildActive = db.activeRebuild() != null ? 1L : 0L;
        } catch (Exception ignored) {
            // Keep explicit -1 values when DB is unavailable.
        }

        long streamPending = -1L;
        if (streamClient != null) {
            try {
                OptionalLong count = streamClient.queryPendingCount();
                if (count.isPresent()) {
                    streamPending = count.getAsLong();
                }
            } catch (Exception ignored) {
                streamPending = -1L;
            }
        }
        return new CachedMetrics(
                nowMs, pending, leased, expiredLeased, dead, protocolFailed, cacheCompPending, rebuildActive, streamPending);
    }

    public record CachedMetrics(
            long capturedAtMs,
            long pendingTasks,
            long leasedTasks,
            long expiredLeasedTasks,
            long deadTasks,
            long protocolFailed,
            long pendingCacheCompensations,
            long rebuildActive,
            long streamPending
    ) {
        static final CachedMetrics UNAVAILABLE = new CachedMetrics(0L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L);
    }
}
