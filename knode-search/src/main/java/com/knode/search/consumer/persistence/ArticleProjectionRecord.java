package com.knode.search.consumer.persistence;

import com.knode.search.consumer.event.ArticleVisibility;

import java.util.List;

public record ArticleProjectionRecord(
        long articleId,
        boolean hasContent,
        long contentRevision,
        ArticleVisibility visibility,
        long counterRevision,
        long viewCount,
        long projectionRevision,
        String title,
        String summary,
        String content,
        Long categoryId,
        String categoryName,
        List<String> tagNames,
        String thumbnail,
        String isTop,
        Long createTimeMs,
        long updatedAtMs
) {
}
