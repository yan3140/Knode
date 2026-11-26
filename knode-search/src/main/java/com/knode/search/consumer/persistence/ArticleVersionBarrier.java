package com.knode.search.consumer.persistence;

import com.knode.search.consumer.event.ArticleVisibility;

public record ArticleVersionBarrier(
        long articleId,
        long maxContentRevision,
        ArticleVisibility lastVisibility,
        long updatedAtMs
) {
}
