package com.knode.search.consumer.event;

import java.util.List;

public sealed interface ParsedContentEvent permits
        ParsedContentEvent.PublicArticleEvent,
        ParsedContentEvent.HiddenArticleEvent,
        ParsedContentEvent.CounterChangedEvent,
        ParsedContentEvent.IgnoredNonSearchEvent {

    String eventId();

    String eventType();

    int schemaVersion();

    String aggregateType();

    String aggregateId();

    long sourceRevision();

    String visibilityRaw();

    record PublicArticleEvent(
            String eventId,
            String eventType,
            int schemaVersion,
            long articleId,
            long sourceRevision,
            String title,
            String summary,
            String content,
            Long categoryId,
            String categoryName,
            List<String> tagNames,
            String thumbnail,
            String isTop,
            long createTimeMs,
            long viewCount,
            long counterRevision
    ) implements ParsedContentEvent {
        @Override
        public String aggregateType() {
            return "ARTICLE";
        }

        @Override
        public String aggregateId() {
            return Long.toString(articleId);
        }

        @Override
        public String visibilityRaw() {
            return ArticleVisibility.PUBLIC.name();
        }
    }

    record HiddenArticleEvent(
            String eventId,
            String eventType,
            int schemaVersion,
            long articleId,
            long sourceRevision,
            ArticleVisibility visibility
    ) implements ParsedContentEvent {
        @Override
        public String aggregateType() {
            return "ARTICLE";
        }

        @Override
        public String aggregateId() {
            return Long.toString(articleId);
        }

        @Override
        public String visibilityRaw() {
            return visibility.name();
        }
    }

    record CounterChangedEvent(
            String eventId,
            String eventType,
            int schemaVersion,
            long articleId,
            long viewCount,
            long counterRevision,
            ArticleVisibility visibility
    ) implements ParsedContentEvent {
        @Override
        public String aggregateType() {
            return "ARTICLE_COUNTER";
        }

        @Override
        public String aggregateId() {
            return Long.toString(articleId);
        }

        @Override
        public long sourceRevision() {
            return counterRevision;
        }

        @Override
        public String visibilityRaw() {
            return visibility.name();
        }
    }

    record IgnoredNonSearchEvent(
            String eventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            long sourceRevision,
            String visibilityRaw
    ) implements ParsedContentEvent {
    }
}
