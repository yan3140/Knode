package com.yan.reliabletask.redisstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "blog.reliable-task.redis-stream")
public class ReliableTaskRedisStreamProperties {

    private long maxLen = 100_000L;
    private final Streams streams = new Streams();
    private final Groups groups = new Groups();
    private final Consumers consumers = new Consumers();

    public long getMaxLen() {
        return maxLen;
    }

    public void setMaxLen(long maxLen) {
        this.maxLen = maxLen;
    }

    public Streams getStreams() {
        return streams;
    }

    public Groups getGroups() {
        return groups;
    }

    public Consumers getConsumers() {
        return consumers;
    }

    public static class Streams {
        private String articleSearch = "yanblog:stream:article-search";
        private String notificationFanout = "yanblog:stream:notification-fanout";
        private String notificationDelivery = "yanblog:stream:notification-delivery";
        private String cacheInvalidate = "yanblog:stream:cache-invalidate";

        public String getArticleSearch() {
            return articleSearch;
        }

        public void setArticleSearch(String articleSearch) {
            this.articleSearch = articleSearch;
        }

        public String getNotificationFanout() {
            return notificationFanout;
        }

        public void setNotificationFanout(String notificationFanout) {
            this.notificationFanout = notificationFanout;
        }

        public String getNotificationDelivery() {
            return notificationDelivery;
        }

        public void setNotificationDelivery(String notificationDelivery) {
            this.notificationDelivery = notificationDelivery;
        }

        public String getCacheInvalidate() {
            return cacheInvalidate;
        }

        public void setCacheInvalidate(String cacheInvalidate) {
            this.cacheInvalidate = cacheInvalidate;
        }
    }

    public static class Groups {
        private String articleSearch = "yanblog-article-search-sync";
        private String notificationFanout = "yanblog-notification-fanout";
        private String notificationDelivery = "yanblog-notification-delivery";
        private String cacheInvalidate = "yanblog-cache-invalidate";

        public String getArticleSearch() {
            return articleSearch;
        }

        public void setArticleSearch(String articleSearch) {
            this.articleSearch = articleSearch;
        }

        public String getNotificationFanout() {
            return notificationFanout;
        }

        public void setNotificationFanout(String notificationFanout) {
            this.notificationFanout = notificationFanout;
        }

        public String getNotificationDelivery() {
            return notificationDelivery;
        }

        public void setNotificationDelivery(String notificationDelivery) {
            this.notificationDelivery = notificationDelivery;
        }

        public String getCacheInvalidate() {
            return cacheInvalidate;
        }

        public void setCacheInvalidate(String cacheInvalidate) {
            this.cacheInvalidate = cacheInvalidate;
        }
    }

    public static class Consumers {
        private String articleSearch = "yanblog-admin";
        private String notificationFanout = "yanblog-admin";
        private String notificationDelivery = "yanblog-admin";
        private String cacheInvalidate = "yanblog-admin";

        public String getArticleSearch() {
            return articleSearch;
        }

        public void setArticleSearch(String articleSearch) {
            this.articleSearch = articleSearch;
        }

        public String getNotificationFanout() {
            return notificationFanout;
        }

        public void setNotificationFanout(String notificationFanout) {
            this.notificationFanout = notificationFanout;
        }

        public String getNotificationDelivery() {
            return notificationDelivery;
        }

        public void setNotificationDelivery(String notificationDelivery) {
            this.notificationDelivery = notificationDelivery;
        }

        public String getCacheInvalidate() {
            return cacheInvalidate;
        }

        public void setCacheInvalidate(String cacheInvalidate) {
            this.cacheInvalidate = cacheInvalidate;
        }
    }
}
