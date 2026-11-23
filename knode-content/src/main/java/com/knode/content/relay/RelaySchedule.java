package com.knode.content.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="KNODE_CONTENT_RELAY_WORKER_ENABLED",havingValue="true")
public class RelaySchedule {
    private static final Logger LOG=LoggerFactory.getLogger(RelaySchedule.class);
    private final RelayWorker worker;
    private final int batch,history;
    public RelaySchedule(RelayWorker worker,@Value("${KNODE_CONTENT_RELAY_BATCH:10}") int batch,
            @Value("${KNODE_CONTENT_RELAY_HISTORY_BATCH:10}") int history,
            @Value("${KNODE_CONTENT_RELAY_DELAY_MS:5000}") long delay) {
        if(batch<1 || batch>10 || history<1 || history>20 || delay<1000 || delay>60000)
            throw new IllegalArgumentException("Invalid relay schedule");
        this.worker=worker;this.batch=batch;this.history=history;
    }
    @Scheduled(initialDelayString="${KNODE_CONTENT_RELAY_DELAY_MS:5000}",fixedDelayString="${KNODE_CONTENT_RELAY_DELAY_MS:5000}")
    public void run() {
        try { worker.runBatch(batch,history); }
        catch(RuntimeException failure) { LOG.warn("Relay worker unavailable"); }
    }
}
