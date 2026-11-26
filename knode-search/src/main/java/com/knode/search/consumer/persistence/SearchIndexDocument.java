package com.knode.search.consumer.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchIndexDocument(
        long id,
        String sourceRevision,
        boolean searchable,
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
    public static SearchIndexDocument upsert(
            long articleId,
            long contentRevision,
            String title,
            String summary,
            String content,
            Long categoryId,
            String categoryName,
            List<String> tagNames,
            String thumbnail,
            long viewCount,
            Long createTimeMs) {
        return new SearchIndexDocument(
                articleId,
                Long.toString(contentRevision),
                true,
                title,
                summary,
                content,
                categoryId,
                categoryName,
                tagNames == null ? List.of() : List.copyOf(tagNames),
                thumbnail,
                viewCount,
                createTimeMs
        );
    }

    public static SearchIndexDocument tombstone(long articleId, long contentRevision) {
        return new SearchIndexDocument(
                articleId,
                Long.toString(contentRevision),
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
