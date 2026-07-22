package com.yan.search.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ArticleSearchMetrics {

    private final Timer querySuccess;
    private final Timer queryFailure;
    private final Counter syncSuccess;
    private final Counter syncFailure;
    private final Counter rebuildSuccess;
    private final Counter rebuildFailure;
    private final Counter outboxSuccess;
    private final Counter outboxFailure;

    public ArticleSearchMetrics(MeterRegistry meterRegistry) {
        this.querySuccess = queryTimer(meterRegistry, "success");
        this.queryFailure = queryTimer(meterRegistry, "failure");
        this.syncSuccess = counter(meterRegistry, "blog.search.sync", "success");
        this.syncFailure = counter(meterRegistry, "blog.search.sync", "failure");
        this.rebuildSuccess = counter(meterRegistry, "blog.search.rebuild", "success");
        this.rebuildFailure = counter(meterRegistry, "blog.search.rebuild", "failure");
        this.outboxSuccess = counter(meterRegistry, "blog.search.outbox", "success");
        this.outboxFailure = counter(meterRegistry, "blog.search.outbox", "failure");
    }

    public void recordQuery(boolean success, long durationNanos) {
        (success ? querySuccess : queryFailure).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordSync(boolean success) {
        (success ? syncSuccess : syncFailure).increment();
    }

    public void recordRebuild(boolean success) {
        (success ? rebuildSuccess : rebuildFailure).increment();
    }

    public void recordOutbox(boolean success, int count) {
        if (count > 0) {
            (success ? outboxSuccess : outboxFailure).increment(count);
        }
    }

    private Timer queryTimer(MeterRegistry registry, String result) {
        return Timer.builder("blog.search.query")
                .description("YanBlog article search latency")
                .tag("result", result)
                .register(registry);
    }

    private Counter counter(MeterRegistry registry, String name, String result) {
        return Counter.builder(name)
                .tag("result", result)
                .register(registry);
    }
}
