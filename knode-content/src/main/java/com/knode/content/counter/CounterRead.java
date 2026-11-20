package com.knode.content.counter;

import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CounterRead {
    private final CounterStore store;
    public CounterRead(CounterStore store) { this.store=store; }
    public Map<Long,Long> latest(Collection<Long> ids) {
        if(!store.enabled() || ids.isEmpty()) return Map.of();
        try { return store.read(ids); }
        catch(RuntimeException failure) { return Map.of(); }
    }
}
