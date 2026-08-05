package com.yan.search.outbox;

public record ArticleSearchOutboxMessage(Long outboxId, Long articleId, String taskKey) {
}
