package com.knode.content.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RelayWorker {
    private static final Logger LOG=LoggerFactory.getLogger(RelayWorker.class);
    private final RelayTransaction transaction;
    private final FactReader facts;
    private final RelayStream stream;
    private final HistoricalRegistrar history;
    public RelayWorker(RelayTransaction transaction,FactReader facts,RelayStream stream,HistoricalRegistrar history) {
        this.transaction=transaction; this.facts=facts; this.stream=stream; this.history=history;
    }
    public int runBatch(int budget,int historyPerFamily) {
        if(budget<1 || budget>10 || historyPerFamily<1 || historyPerFamily>20 || !stream.enabled()
                || TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Relay unavailable");
        history.runBatch(historyPerFamily);
        int processed=0;
        for(int i=0;i<budget;i++) {
            RelayTransaction.Claim claim=transaction.claim();
            if(claim==null) break;
            if(claim.exhausted()) continue;
            String raw;
            try { raw=facts.load(claim); }
            catch(EventGuard.InvalidEvent failure) { markFailure(claim,"INVALID_EVENT",true); continue; }
            catch(FactReader.MissingFact failure) { markFailure(claim,"MISSING_FACT",true); continue; }
            catch(RuntimeException failure) { markFailure(claim,"DATABASE",false); continue; }
            String id;
            try { id=stream.publish(raw); }
            catch(RuntimeException failure) { markFailure(claim,"TRANSPORT",false); continue; }
            try {
                if(transaction.sent(claim,id)) processed++;
                else LOG.warn("Relay lease expired before completion");
            } catch(RuntimeException failure) { markFailure(claim,"DATABASE",false); }
        }
        return processed;
    }
    private void markFailure(RelayTransaction.Claim claim,String category,boolean permanent) {
        try { transaction.failed(claim,category,permanent); }
        catch(RuntimeException failure) { LOG.warn("Relay state retained for lease recovery"); }
    }
}
