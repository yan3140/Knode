package com.knode.search.consumer.indexer;

import com.knode.search.consumer.config.SearchConsumerProperties;
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
@ConditionalOnProperty(prefix = "knode.search.consumer.indexer", name = "worker-enabled", havingValue = "true")
public class SearchIndexWorkerScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchIndexWorkerScheduler.class);

    private final SearchIndexTaskWorker worker;
    private final long pollDelayMs;
    private ScheduledExecutorService executor;

    public SearchIndexWorkerScheduler(SearchIndexTaskWorker worker, SearchConsumerProperties properties) {
        this.worker = worker;
        this.pollDelayMs = Math.max(100L, properties.getIndexer().getPollDelayMs());
    }

    @PostConstruct
    void start() {
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knode-search-index-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.scheduleWithFixedDelay(this::runSafely, pollDelayMs, pollDelayMs, TimeUnit.MILLISECONDS);
    }

    private void runSafely() {
        try {
            worker.processOnce();
        } catch (Exception ex) {
            LOGGER.warn("event=search_index_worker_error errorType={}", ex.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
