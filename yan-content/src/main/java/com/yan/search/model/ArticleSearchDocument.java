package com.yan.search.model;

import java.util.List;

public record ArticleSearchDocument(
        Long id,
        String title,
        String summary,
        String content,
        Long categoryId,
        String categoryName,
        List<String> tagNames,
        String thumbnail,
        Long viewCount,
        Long createTime
) {
}
