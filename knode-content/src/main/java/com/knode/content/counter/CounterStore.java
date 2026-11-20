package com.knode.content.counter;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CounterStore extends AutoCloseable {
    record Identity(String ip, String article, String visitor) {}
    record Snapshot(long articleId, long value, String lease) {}
    record Claim(List<Snapshot> rows, int invalid) {}
    boolean enabled();
    int hit(long articleId, long baseline, boolean visible, Identity identity);
    Map<Long,Long> read(Collection<Long> articleIds);
    Claim claim();
    void finish(Snapshot snapshot);
    long pending();
    @Override default void close() {}

    final class Disabled implements CounterStore {
        @Override public boolean enabled() { return false; }
        @Override public int hit(long id,long baseline,boolean visible,Identity identity) { throw new CounterUnavailable(); }
        @Override public Map<Long,Long> read(Collection<Long> ids) { return Map.of(); }
        @Override public Claim claim() { throw new CounterUnavailable(); }
        @Override public void finish(Snapshot snapshot) { throw new CounterUnavailable(); }
        @Override public long pending() { throw new CounterUnavailable(); }
    }
}
