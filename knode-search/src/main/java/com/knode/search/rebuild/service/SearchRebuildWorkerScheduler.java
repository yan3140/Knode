package com.knode.search.rebuild.service;

import com.knode.search.rebuild.config.SearchRebuildProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "knode.search.rebuild", name = "worker-enabled", havingValue = "true")
public class SearchRebuildWorkerScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchRebuildWorkerScheduler.class);

    private final SearchRebuildService rebuildService;
    private final long pollDelayMs;
    private ScheduledExecutorService executor;

    public SearchRebuildWorkerScheduler(SearchRebuildService rebuildService, SearchRebuildProperties properties) {
        this.rebuildService = rebuildService;
        this.pollDelayMs = Math.max(100L, properties.getPollDelayMs());
    }

    @PostConstruct
    void start() {
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knode-search-rebuild-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.scheduleWithFixedDelay(this::runSafely, pollDelayMs, pollDelayMs, TimeUnit.MILLISECONDS);
    }

    private void runSafely() {
        try {
            rebuildService.advanceActiveJob();
        } catch (Exception ex) {
            LOGGER.warn("event=search_rebuild_worker_error errorType={}", ex.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
