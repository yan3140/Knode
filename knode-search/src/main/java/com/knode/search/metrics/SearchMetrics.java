package com.knode.search.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SearchMetrics {
    private final Timer querySuccess;
    private final Timer queryFailure;
    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Counter cacheFailure;
    private final Counter visibilityExcluded;

    public SearchMetrics(MeterRegistry registry) {
        this.querySuccess = Timer.builder("knode.search.query")
                .description("Knode public article search latency")
                .tag("result", "success")
                .register(registry);
        this.queryFailure = Timer.builder("knode.search.query")
                .description("Knode public article search latency")
                .tag("result", "failure")
                .register(registry);
        this.cacheHit = Counter.builder("knode.search.cache")
                .tag("outcome", "hit")
                .register(registry);
        this.cacheMiss = Counter.builder("knode.search.cache")
                .tag("outcome", "miss")
                .register(registry);
        this.cacheFailure = Counter.builder("knode.search.cache")
                .tag("outcome", "failure")
                .register(registry);
        this.visibilityExcluded = Counter.builder("knode.search.visibility.excluded")
                .register(registry);
    }

    public void recordQuery(boolean success, long durationNanos) {
        (success ? querySuccess : queryFailure).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordCacheHit() {
        cacheHit.increment();
    }

    public void recordCacheMiss() {
        cacheMiss.increment();
    }

    public void recordCacheFailure() {
        cacheFailure.increment();
    }

    public void recordVisibilityExcluded(int count) {
        if (count > 0) {
            visibilityExcluded.increment(count);
        }
    }
}
