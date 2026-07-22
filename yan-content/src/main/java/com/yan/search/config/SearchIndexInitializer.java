package com.yan.search.config;

import com.yan.search.repository.ArticleSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class SearchIndexInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchIndexInitializer.class);

    private final ArticleSearchRepository searchRepository;

    public SearchIndexInitializer(ArticleSearchRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    @Async("searchSyncExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            searchRepository.ensureIndex();
            LOGGER.info("文章搜索索引检查完成");
        } catch (Exception initializeFailure) {
            LOGGER.warn("Elasticsearch 当前不可用，应用继续启动，后续由同步补偿恢复", initializeFailure);
        }
    }
}
