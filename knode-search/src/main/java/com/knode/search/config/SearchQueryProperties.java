package com.knode.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knode.search.query")
public class SearchQueryProperties {
    private int maxKeywordLength = 40;
    private int defaultPageSize = 5;
    private int maxPageSize = 20;
    private int maxPageNum = 50;
    private int totalHitThreshold = 10_000;
    private boolean highlightContent = false;
    private long totalTimeoutMs = 3_000L;

    public int getMaxKeywordLength() {
        return maxKeywordLength;
    }

    public void setMaxKeywordLength(int maxKeywordLength) {
        this.maxKeywordLength = maxKeywordLength;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getMaxPageNum() {
        return maxPageNum;
    }

    public void setMaxPageNum(int maxPageNum) {
        this.maxPageNum = maxPageNum;
    }

    public int getTotalHitThreshold() {
        return totalHitThreshold;
    }

    public void setTotalHitThreshold(int totalHitThreshold) {
        this.totalHitThreshold = totalHitThreshold;
    }

    public boolean isHighlightContent() {
        return highlightContent;
    }

    public void setHighlightContent(boolean highlightContent) {
        this.highlightContent = highlightContent;
    }

    public long getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    public void setTotalTimeoutMs(long totalTimeoutMs) {
        this.totalTimeoutMs = totalTimeoutMs;
    }
}
