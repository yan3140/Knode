package com.knode.content.counter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="KNODE_CONTENT_COUNTER_WORKER_ENABLED",havingValue="true")
public class CounterSchedule {
    private static final Logger LOG=LoggerFactory.getLogger(CounterSchedule.class);
    private final CounterWorker worker;
    private final int batch;
    public CounterSchedule(CounterWorker worker,@Value("${KNODE_CONTENT_COUNTER_WORKER_BATCH:10}") int batch,
            @Value("${KNODE_CONTENT_COUNTER_WORKER_DELAY_MS:5000}") long delay) {
        if(batch<1 || batch>10 || delay<1000 || delay>60000) throw new IllegalArgumentException("Invalid counter worker settings");
        this.worker=worker; this.batch=batch;
    }
    @Scheduled(initialDelayString="${KNODE_CONTENT_COUNTER_WORKER_DELAY_MS:5000}",
               fixedDelayString="${KNODE_CONTENT_COUNTER_WORKER_DELAY_MS:5000}")
    public void run() {
        try { worker.runBatch(batch); }
        catch(RuntimeException failure) { LOG.warn("Counter worker unavailable"); }
    }
}
