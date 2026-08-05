package com.yan.reliabletask;

/** Builds stable idempotency keys for reliable asynchronous tasks. */
public final class ReliableTaskKeys {
    private ReliableTaskKeys() {
    }

    public static String articleSearch(Long articleId) {
        return "ARTICLE_SEARCH:" + articleId;
    }

    public static String articlePublishNotification(Long authorId, Long articleId) {
        return "ARTICLE_PUBLISH_NOTIFY:" + authorId + ":" + articleId;
    }

    public static String directNotification(String type, Long receiverId, Long actorId,
                                            Long articleId, Long commentId, Long rootCommentId) {
        if (commentId != null) {
            return type + ":" + commentId;
        }
        if ("FOLLOWED".equals(type)) {
            return "FOLLOWED:" + actorId + ":" + receiverId;
        }
        return type + ":" + receiverId + ":" + actorId + ":" + articleId + ":" + rootCommentId;
    }
}
