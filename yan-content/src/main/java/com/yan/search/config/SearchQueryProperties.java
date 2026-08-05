package com.yan.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "blog.search.query")
public class SearchQueryProperties {
    private int maxKeywordLength = 40;
    private int defaultPageSize = 5;
    private int maxPageSize = 20;
    private int maxPageNum = 50;
    private int totalHitThreshold = 10_000;
    private boolean highlightContent = false;
    private final Cache cache = new Cache();

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

    public Cache getCache() {
        return cache;
    }

    public static class Cache {
        private boolean enabled = true;
        private int maxPageSize = 20;
        private int admissionThreshold = 2;
        private int admissionTtlSeconds = 60;
        private int resultTtlSeconds = 60;
        private int emptyTtlSeconds = 30;
        private int jitterSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize;
        }

        public int getAdmissionThreshold() {
            return admissionThreshold;
        }

        public void setAdmissionThreshold(int admissionThreshold) {
            this.admissionThreshold = admissionThreshold;
        }

        public int getAdmissionTtlSeconds() {
            return admissionTtlSeconds;
        }

        public void setAdmissionTtlSeconds(int admissionTtlSeconds) {
            this.admissionTtlSeconds = admissionTtlSeconds;
        }

        public int getResultTtlSeconds() {
            return resultTtlSeconds;
        }

        public void setResultTtlSeconds(int resultTtlSeconds) {
            this.resultTtlSeconds = resultTtlSeconds;
        }

        public int getEmptyTtlSeconds() {
            return emptyTtlSeconds;
        }

        public void setEmptyTtlSeconds(int emptyTtlSeconds) {
            this.emptyTtlSeconds = emptyTtlSeconds;
        }

        public int getJitterSeconds() {
            return jitterSeconds;
        }

        public void setJitterSeconds(int jitterSeconds) {
            this.jitterSeconds = jitterSeconds;
        }
    }
}
