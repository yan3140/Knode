package com.knode.community.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CommunityWorkerSchedule {
    private static final Logger LOG = LoggerFactory.getLogger(CommunityWorkerSchedule.class);

    private final CommunityContentStream stream;
    private final NotificationDeliveryWorker deliveryWorker;
    private final boolean streamEnabled;
    private final boolean workerEnabled;

    public CommunityWorkerSchedule(
            CommunityContentStream stream,
            NotificationDeliveryWorker deliveryWorker,
            @Value("${KNODE_COMMUNITY_STREAM_ENABLED:false}") boolean streamEnabled,
            @Value("${KNODE_COMMUNITY_WORKER_ENABLED:false}") boolean workerEnabled) {
        this.stream = stream;
        this.deliveryWorker = deliveryWorker;
        this.streamEnabled = streamEnabled;
        this.workerEnabled = workerEnabled;
    }

    @Scheduled(fixedDelayString = "${KNODE_COMMUNITY_STREAM_POLL_DELAY_MS:500}")
    public void pollStream() {
        if (!streamEnabled || !stream.enabled()) {
            return;
        }
        try {
            stream.pollAndIngestOnce();
        } catch (RuntimeException failure) {
            LOG.warn("event=community_stream_poll_failed errorType={}", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${KNODE_COMMUNITY_WORKER_POLL_DELAY_MS:500}")
    public void runDeliveryWorkers() {
        if (!workerEnabled) {
            return;
        }
        try {
            deliveryWorker.processDirectBatch();
        } catch (RuntimeException failure) {
            LOG.warn("event=community_direct_worker_failed errorType={}", failure.getClass().getSimpleName());
        }
        try {
            deliveryWorker.processFanoutBatch();
        } catch (RuntimeException failure) {
            LOG.warn("event=community_fanout_worker_failed errorType={}", failure.getClass().getSimpleName());
        }
    }
}
