package com.yan.service;

import java.util.Collection;

public interface ArticleCounterService {

    boolean increaseViewCount(Long articleId, String visitorFingerprint);

    Long getViewCount(Long articleId, Long databaseValue);

    int flushDirtyCounters(int batchSize);

    void initializeCounters();

    void synchronizeArticle(Long articleId);

    void removeArticles(Collection<Long> articleIds);
}
