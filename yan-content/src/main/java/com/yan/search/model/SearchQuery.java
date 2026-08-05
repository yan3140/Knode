package com.yan.search.model;

public record SearchQuery(
        String keyword,
        int pageNum,
        int pageSize,
        SearchSort sort,
        boolean highlightContent,
        int totalHitThreshold,
        boolean cacheCandidate,
        String cacheKey
) {
    public int from() {
        return (pageNum - 1) * pageSize;
    }
}
