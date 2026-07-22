package com.yan.search.outbox;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.search.metrics.ArticleSearchMetrics;
import com.yan.search.service.ArticleSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ArticleSearchOutboxService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleSearchOutboxService.class);
    private static final String PENDING = "PENDING";

    private final ArticleSearchOutboxMapper outboxMapper;
    private final ArticleSearchService articleSearchService;
    private final ArticleSearchMetrics searchMetrics;
    private final int leaseSeconds;
    private final int batchSize;
    private final int retryDelaySeconds;
    private final int maxRetryDelaySeconds;

    public ArticleSearchOutboxService(ArticleSearchOutboxMapper outboxMapper,
                                      ArticleSearchService articleSearchService,
                                      ArticleSearchMetrics searchMetrics,
                                      @Value("${blog.search.outbox.lease-seconds:30}") int leaseSeconds,
                                      @Value("${blog.search.outbox.batch-size:100}") int batchSize,
                                      @Value("${blog.search.outbox.retry-delay-seconds:10}") int retryDelaySeconds,
                                      @Value("${blog.search.outbox.max-retry-delay-seconds:300}") int maxRetryDelaySeconds) {
        if (leaseSeconds < 1 || batchSize < 1 || retryDelaySeconds < 1
                || maxRetryDelaySeconds < retryDelaySeconds) {
            throw new IllegalArgumentException("Search outbox timing configuration must be positive");
        }
        this.outboxMapper = outboxMapper;
        this.articleSearchService = articleSearchService;
        this.searchMetrics = searchMetrics;
        this.leaseSeconds = leaseSeconds;
        this.batchSize = batchSize;
        this.retryDelaySeconds = retryDelaySeconds;
        this.maxRetryDelaySeconds = maxRetryDelaySeconds;
    }

    public void enqueue(Long articleId) {
        if (articleId == null) {
            return;
        }
        ArticleSearchOutboxEvent event = new ArticleSearchOutboxEvent();
        event.setArticleId(articleId);
        event.setStatus(PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(new Date());
        if (outboxMapper.insert(event) != 1) {
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
        try {
            Set<Long> failedArticleIds = articleSearchService.synchronizeForOutbox(
                    claimed.stream().map(ArticleSearchOutboxEvent::getArticleId).toList());
            int succeeded = 0;
            int failed = 0;
            for (ArticleSearchOutboxEvent event : claimed) {
                if (failedArticleIds.contains(event.getArticleId())) {
                    markForRetry(event, "Elasticsearch synchronization failed");
                    failed++;
                } else {
                    outboxMapper.markSucceeded(event.getId());
                    succeeded++;
                }
            }
            searchMetrics.recordOutbox(true, succeeded);
            searchMetrics.recordOutbox(false, failed);
            LOGGER.info("event=article_search_outbox_batch_completed claimed={} succeeded={} failed={}",
                    claimed.size(), succeeded, failed);
        } catch (RuntimeException failure) {
            claimed.forEach(event -> markForRetry(event, failure.getMessage()));
            searchMetrics.recordOutbox(false, claimed.size());
        }
    }

    public ResponseResult replayPending() {
        int requeued = outboxMapper.requeuePending();
        return ResponseResult.okResult(Map.of("requeued", requeued));
    }

    private void markForRetry(ArticleSearchOutboxEvent event, String reason) {
        String safeReason = reason == null ? "Unknown synchronization failure" : reason;
        if (safeReason.length() > 1000) {
            safeReason = safeReason.substring(0, 1000);
        }
        int delaySeconds = retryDelaySeconds(event.getRetryCount());
        outboxMapper.markForRetry(event.getId(), delaySeconds, safeReason);
        LOGGER.warn("event=article_search_outbox_retry_pending outboxId={} articleId={} retryDelaySeconds={}",
                event.getId(), event.getArticleId(), delaySeconds);
    }

    private int retryDelaySeconds(Integer retryCount) {
        int attempts = retryCount == null ? 0 : Math.max(0, retryCount);
        long delay = retryDelaySeconds;
        for (int index = 0; index < attempts && delay < maxRetryDelaySeconds; index++) {
            delay = Math.min((long) maxRetryDelaySeconds, delay * 2);
        }
        return (int) delay;
    }
}
