package com.knode.content.relay;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HistoricalRegistrar {
    private static final Logger LOG=LoggerFactory.getLogger(HistoricalRegistrar.class);
    private final HistoryTransaction transaction;
    public HistoricalRegistrar(HistoryTransaction transaction) { this.transaction=transaction; }
    public int runBatch(int perFamily) {
        if(perFamily<1 || perFamily>20) throw new IllegalArgumentException("Invalid batch");
        int total=0;
        for(String family:List.of("ARTICLE","COMMENT","COUNTER")) {
            try { total+=transaction.scan(family,perFamily); }
            catch(RuntimeException failure) {
                LOG.warn("History scan failed for family {}",family);
                try { transaction.recordFailure(family); }
                catch(RuntimeException unavailable) { LOG.warn("History failure state unavailable"); }
            }
        }
        return total;
    }
}
