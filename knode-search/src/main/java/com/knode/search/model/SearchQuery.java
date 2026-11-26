package com.knode.search.model;

public record SearchQuery(
        String keyword,
        int pageNum,
        int pageSize,
        SearchSort sort,
        boolean highlightContent,
        int totalHitThreshold,
        boolean cacheCandidate,
        String resultCacheKey,
        String admissionCacheKey
) {
    public int from() {
        return (pageNum - 1) * pageSize;
    }
}
