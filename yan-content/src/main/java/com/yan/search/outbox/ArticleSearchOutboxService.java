package com.yan.search.outbox;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.reliabletask.ReliableTaskKeys;
import com.yan.reliabletask.ReliableTaskPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ArticleSearchOutboxService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleSearchOutboxService.class);

    private final ArticleSearchOutboxMapper outboxMapper;
    private final ArticleSearchOutboxProcessor processor;
    private final ArticleSearchOutboxMessageGateway messageGateway;
    private final int leaseSeconds;
    private final int batchSize;
    private final boolean streamEnabled;
    private final ReliableTaskPolicy taskPolicy;

    public ArticleSearchOutboxService(ArticleSearchOutboxMapper outboxMapper,
                                      ArticleSearchOutboxProcessor processor,
                                      List<ArticleSearchOutboxMessageGateway> messageGateways,
                                      @Value("${blog.search.outbox.lease-seconds:30}") int leaseSeconds,
                                      @Value("${blog.search.outbox.batch-size:100}") int batchSize,
                                      @Value("${blog.search.outbox.retry-delay-seconds:10}") int retryDelaySeconds,
                                      @Value("${blog.search.outbox.max-retry-delay-seconds:300}") int maxRetryDelaySeconds,
                                      @Value("${blog.search.outbox.max-retry:8}") int maxRetry,
                                      @Value("${blog.search.outbox.stream.enabled:false}") boolean streamEnabled) {
        if (leaseSeconds < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Search outbox timing configuration must be positive");
        }
        this.outboxMapper = outboxMapper;
        this.processor = processor;
        this.messageGateway = messageGateways.isEmpty() ? null : messageGateways.get(0);
        this.leaseSeconds = leaseSeconds;
        this.batchSize = batchSize;
        this.streamEnabled = streamEnabled;
        this.taskPolicy = new ReliableTaskPolicy(maxRetry, retryDelaySeconds, maxRetryDelaySeconds);
    }

    public void enqueue(Long articleId) {
        if (articleId == null) {
            return;
        }
        if (outboxMapper.enqueue(articleId, ReliableTaskKeys.articleSearch(articleId), taskPolicy.maxRetry()) < 1) {
            throw new IllegalStateException("Unable to persist article search outbox event");
        }
    }

    @Scheduled(fixedDelayString = "${blog.search.outbox.poll-delay-ms:1000}")
    public void deliverPending() {
        outboxMapper.recoverExpiredClaims();
        List<ArticleSearchOutboxEvent> candidates = outboxMapper.selectPending(batchSize);
        if (candidates.isEmpty()) {
            return;
        }
        List<ArticleSearchOutboxEvent> claimed = new ArrayList<>();
        for (ArticleSearchOutboxEvent event : candidates) {
            if (outboxMapper.claim(event.getId(), leaseSeconds) == 1) {
                claimed.add(event);
            }
        }
        if (claimed.isEmpty()) {
            return;
        }
        if (streamEnabled && messageGateway != null) {
            relayToRedisStream(claimed);
            return;
        }
        if (streamEnabled) {
            LOGGER.warn("event=article_search_outbox_stream_gateway_missing action=direct_fallback claimed={}",
                    claimed.size());
        }
        processor.process(claimed);
    }

    private void relayToRedisStream(List<ArticleSearchOutboxEvent> claimed) {
        int published = 0;
        int failed = 0;
        for (ArticleSearchOutboxEvent event : claimed) {
            try {
                messageGateway.publish(event);
                published++;
            } catch (RuntimeException publishFailure) {
                processor.markForRetry(event, publishFailure.getMessage());
                failed++;
            }
        }
        LOGGER.info("event=article_search_outbox_relay_completed claimed={} published={} failed={}",
                claimed.size(), published, failed);
    }

    public ResponseResult replayPending() {
        int requeued = outboxMapper.requeuePending();
        return ResponseResult.okResult(Map.of("requeued", requeued));
    }
}
