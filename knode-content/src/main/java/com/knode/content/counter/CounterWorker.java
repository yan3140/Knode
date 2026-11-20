package com.knode.content.counter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CounterWorker {
    private static final Logger LOG=LoggerFactory.getLogger(CounterWorker.class);
    private final CounterStore store;
    private final CounterFlushTransaction transaction;
    public CounterWorker(CounterStore store,CounterFlushTransaction transaction) { this.store=store; this.transaction=transaction; }
    public int runBatch(int budget) {
        if(budget<1 || budget>10 || !store.enabled() || TransactionSynchronizationManager.isActualTransactionActive())
            throw new CounterUnavailable();
        int processed=0;
        for(int i=0;i<budget;i++) {
            CounterStore.Claim claim=store.claim();
            if(claim.invalid()>0) LOG.warn("Invalid counter dirty entry retained for investigation");
            if(claim.rows().isEmpty()) { if(claim.invalid()==0) break; else continue; }
            CounterStore.Snapshot snapshot=claim.rows().get(0);
            try {
                transaction.persist(snapshot);
                store.finish(snapshot);
                processed++;
            } catch(RuntimeException failure) {
                LOG.warn("Counter snapshot retained for retry");
            }
        }
        return processed;
    }
}
