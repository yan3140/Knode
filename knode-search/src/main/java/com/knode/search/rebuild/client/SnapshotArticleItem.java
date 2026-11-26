package com.knode.search.rebuild.client;

import java.util.List;

public record SnapshotArticleItem(
        long articleId,
        long sourceRevision,
        String publicationFactId,
        long authorId,
        String title,
        String summary,
        String content,
        String thumbnail,
        String isTop,
        String isComment,
        Long categoryId,
        String categoryName,
        List<String> tagNames,
        long createTimeMs,
        Long publishedAtMs,
        long viewCount,
        long counterRevision
) {
}
