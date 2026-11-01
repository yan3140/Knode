package com.knode.user;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

final class MutableTestClock extends Clock {
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    void reset() {now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));}
    void set(Instant value) {now.set(value);}
    @Override public ZoneId getZone() {return ZoneOffset.UTC;}
    @Override public Clock withZone(ZoneId zone) {if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException();return this;}
    @Override public Instant instant() {return now.get();}
}
