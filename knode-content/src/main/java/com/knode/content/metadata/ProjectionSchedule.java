package com.knode.content.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="knode.content.metadata-worker",name="enabled",havingValue="true")
public class ProjectionSchedule {
    private static final Logger LOG=LoggerFactory.getLogger(ProjectionSchedule.class);
    private final ProjectionWorker worker;
    private final int batch;
    public ProjectionSchedule(ProjectionWorker worker,
            @Value("${knode.content.metadata-worker.batch-size}") int batch,
            @Value("${knode.content.metadata-worker.delay-ms}") long delay) {
        if(batch<1 || batch>100 || delay<1000 || delay>60000) throw new IllegalArgumentException("Invalid metadata worker settings");
        this.worker=worker; this.batch=batch;
    }
    @Scheduled(fixedDelayString="${knode.content.metadata-worker.delay-ms}")
    public void run() {
        try { worker.runBatch(batch); } catch (RuntimeException failure) { LOG.warn("Metadata projection worker unavailable"); }
    }
}
