package com.yan.search.outbox;

import com.yan.domain.entity.ArticleSearchOutboxEvent;

public interface ArticleSearchOutboxMessageGateway {
    void publish(ArticleSearchOutboxEvent event);
}
