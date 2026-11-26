package com.knode.search.model;

import java.time.Duration;

public final class QueryBudget {
    private final long deadlineNanos;

    private QueryBudget(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static QueryBudget start(Duration totalBudget) {
        long nanos = Math.max(1L, totalBudget.toNanos());
        return new QueryBudget(System.nanoTime() + nanos);
    }

    public long remainingMillis() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        return Math.max(1L, remainingNanos / 1_000_000L);
    }

    public boolean isExpired() {
        return deadlineNanos - System.nanoTime() <= 0L;
    }

    public Duration cap(Duration stepBudget) {
        long remainingMs = remainingMillis();
        if (remainingMs <= 0L) {
            return Duration.ZERO;
        }
        long stepMs = Math.max(1L, stepBudget.toMillis());
        return Duration.ofMillis(Math.min(remainingMs, stepMs));
    }
}
