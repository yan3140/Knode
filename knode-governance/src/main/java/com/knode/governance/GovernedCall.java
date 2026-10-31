package com.knode.governance;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import java.util.concurrent.Callable;

/** Wrap an explicitly named synchronous Feign or other dependency call. */
public final class GovernedCall {
    public <T> T execute(String resource, Callable<T> call) {
        if (resource == null || !resource.matches("feign:[A-Za-z0-9._-]+#[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException("Resource must be feign:<client>#<operation>");
        Entry entry;
        try { entry = SphU.entry(resource, EntryType.OUT); }
        catch (BlockException blocked) { throw new GovernanceUnavailableException(blocked); }
        try {
            return call.call();
        } catch (Exception failure) {
            Tracer.traceEntry(failure, entry);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new GovernanceUnavailableException(failure);
        } finally { entry.exit(); }
    }
}
