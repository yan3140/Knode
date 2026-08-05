package com.yan.search.outbox;

import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.reliabletask.ReliableTaskPolicy;
import com.yan.reliabletask.ReliableTaskStatus;
import com.yan.search.metrics.ArticleSearchMetrics;
import com.yan.search.service.ArticleSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class ArticleSearchOutboxProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleSearchOutboxProcessor.class);

    private final ArticleSearchOutboxMapper outboxMapper;
    private final ArticleSearchService articleSearchService;
    private final ArticleSearchMetrics searchMetrics;
    private final ReliableTaskPolicy taskPolicy;

    public ArticleSearchOutboxProcessor(ArticleSearchOutboxMapper outboxMapper,
                                        ArticleSearchService articleSearchService,
                                        ArticleSearchMetrics searchMetrics,
                                        @Value("${blog.search.outbox.retry-delay-seconds:10}") int retryDelaySeconds,
                                        @Value("${blog.search.outbox.max-retry-delay-seconds:300}") int maxRetryDelaySeconds,
                                        @Value("${blog.search.outbox.max-retry:8}") int maxRetry) {
        this.outboxMapper = outboxMapper;
        this.articleSearchService = articleSearchService;
        this.searchMetrics = searchMetrics;
        this.taskPolicy = new ReliableTaskPolicy(maxRetry, retryDelaySeconds, maxRetryDelaySeconds);
    }

    public void process(List<ArticleSearchOutboxEvent> claimed) {
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        claimed.forEach(event -> outboxMapper.markProcessingStarted(event.getId()));
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

    public void markForRetry(ArticleSearchOutboxEvent event, String reason) {
        String safeReason = taskPolicy.safeError(reason);
        int delaySeconds = taskPolicy.nextRetryDelaySeconds(event.getRetryCount());
        outboxMapper.markForRetry(event.getId(), delaySeconds, safeReason);
        String nextStatus = taskPolicy.willDeadLetter(event.getRetryCount())
                ? ReliableTaskStatus.FAILED : ReliableTaskStatus.PENDING;
        LOGGER.warn("event=article_search_outbox_retry_scheduled outboxId={} articleId={} nextStatus={} retryDelaySeconds={}",
                event.getId(), event.getArticleId(), nextStatus, delaySeconds);
    }
}
