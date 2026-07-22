package com.yan.blog.job;

import com.yan.constants.CacheConstants;
import com.yan.service.ArticleCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UpdateViewCountJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateViewCountJob.class);

    private final ArticleCounterService articleCounterService;

    public UpdateViewCountJob(ArticleCounterService articleCounterService) {
        this.articleCounterService = articleCounterService;
    }

    @Scheduled(
            fixedDelayString = "${blog.article-counter.flush-delay-ms:60000}",
            initialDelayString = "${blog.article-counter.flush-initial-delay-ms:60000}"
    )
    public void updateViewCount() {
        try {
            int processed = articleCounterService.flushDirtyCounters(
                    CacheConstants.ARTICLE_VIEW_FLUSH_BATCH_SIZE);
            if (processed > 0) {
                LOGGER.info("文章浏览量批量落库完成，processed={}", processed);
            }
        } catch (RuntimeException flushFailure) {
            LOGGER.warn("文章浏览量批量落库失败，dirty 标记保留等待重试", flushFailure);
        }
    }
}
