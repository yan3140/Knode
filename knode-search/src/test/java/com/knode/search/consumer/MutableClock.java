package com.knode.search.consumer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

public final class MutableClock extends Clock {
    private final AtomicLong epochMillis;

    public MutableClock(Instant initial) {
        this.epochMillis = new AtomicLong(initial.toEpochMilli());
    }

    public void advance(Duration duration) {
        epochMillis.addAndGet(duration.toMillis());
    }

    public void setInstant(Instant instant) {
        epochMillis.set(instant.toEpochMilli());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(epochMillis.get());
    }
}
