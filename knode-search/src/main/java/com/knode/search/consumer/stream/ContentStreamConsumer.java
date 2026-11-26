package com.knode.search.consumer.stream;

import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.persistence.ContentEventIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "knode.search.consumer.stream", name = "enabled", havingValue = "true")
public class ContentStreamConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContentStreamConsumer.class);

    private final ContentStreamClient streamClient;
    private final ContentEventIngestionService ingestionService;
    private final SearchConsumerProperties.StreamProps streamProps;

    public ContentStreamConsumer(
            ContentStreamClient streamClient,
            ContentEventIngestionService ingestionService,
            SearchConsumerProperties properties) {
        this.streamClient = streamClient;
        this.ingestionService = ingestionService;
        this.streamProps = properties.getStream();
    }

    public PollSummary pollOnce() {
        int batchSize = Math.max(1, streamProps.getBatchSize());
        long minIdleMs = Math.max(10L, streamProps.getMinIdleMs());
        long blockTimeoutMs = Math.max(10L, streamProps.getBlockTimeoutMs());

        List<StreamRecord> pending = streamClient.claimIdlePending(batchSize, minIdleMs);
        BatchCounters pendingCounters = processBatch(pending);

        List<StreamRecord> fresh = streamClient.readNew(batchSize, blockTimeoutMs);
        BatchCounters freshCounters = processBatch(fresh);

        return new PollSummary(
                pending.size(),
                fresh.size(),
                pendingCounters.acked + freshCounters.acked,
                pendingCounters.dbFailed + freshCounters.dbFailed,
                pendingCounters.ackFailed + freshCounters.ackFailed,
                pendingCounters.protocolFailed + freshCounters.protocolFailed
        );
    }

    private BatchCounters processBatch(List<StreamRecord> records) {
        BatchCounters counters = new BatchCounters();
        for (StreamRecord record : records) {
            if (record == null || record.recordId() == null) {
                continue;
            }
            ContentEventIngestionService.IngestionOutcome outcome;
            try {
                outcome = ingestionService.ingestStreamMessage(record.recordId(), record.fields());
            } catch (Exception dbFailure) {
                counters.dbFailed++;
                LOGGER.warn("event=search_stream_ingest_failed stream={} group={} consumer={} recordId={} errorType={}",
                        streamClient.streamKey(), streamClient.groupName(), streamClient.consumerName(),
                        record.recordId(), dbFailure.getClass().getSimpleName());
                // Do NOT XACK when SQL transaction fails or rolls back; leave pending for reclaim.
                continue;
            }

            if (outcome == ContentEventIngestionService.IngestionOutcome.PROTOCOL_FAILED_RECORDED) {
                counters.protocolFailed++;
            }

            // SQL transaction has committed; now execute XACK.
            try {
                long acked = streamClient.ack(List.of(record.recordId()));
                if (acked > 0L) {
                    counters.acked++;
                }
            } catch (Exception ackFailure) {
                counters.ackFailed++;
                LOGGER.warn("event=search_stream_ack_failed stream={} group={} consumer={} recordId={} errorType={}",
                        streamClient.streamKey(), streamClient.groupName(), streamClient.consumerName(),
                        record.recordId(), ackFailure.getClass().getSimpleName());
            }
        }
        return counters;
    }

    private static final class BatchCounters {
        int acked;
        int dbFailed;
        int ackFailed;
        int protocolFailed;
    }

    public record PollSummary(
            int reclaimedCount,
            int freshCount,
            int ackedCount,
            int dbFailedCount,
            int ackFailedCount,
            int protocolFailedCount
    ) {
    }
}
