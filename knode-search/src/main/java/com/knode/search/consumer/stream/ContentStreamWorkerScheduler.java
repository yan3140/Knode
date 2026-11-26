package com.knode.search.consumer.stream;

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
@ConditionalOnProperty(prefix = "knode.search.consumer.stream", name = "worker-enabled", havingValue = "true")
public class ContentStreamWorkerScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContentStreamWorkerScheduler.class);

    private final ContentStreamConsumer consumer;
    private final long pollDelayMs;
    private ScheduledExecutorService executor;

    public ContentStreamWorkerScheduler(ContentStreamConsumer consumer, SearchConsumerProperties properties) {
        this.consumer = consumer;
        this.pollDelayMs = Math.max(100L, properties.getStream().getPollDelayMs());
    }

    @PostConstruct
    void start() {
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knode-search-stream-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.scheduleWithFixedDelay(this::runSafely, pollDelayMs, pollDelayMs, TimeUnit.MILLISECONDS);
    }

    private void runSafely() {
        try {
            consumer.pollOnce();
        } catch (Exception ex) {
            LOGGER.warn("event=search_stream_poll_error errorType={}", ex.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
