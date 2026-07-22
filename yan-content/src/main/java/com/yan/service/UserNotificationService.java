package com.yan.service;

import com.yan.domain.ResponseResult;

public interface UserNotificationService {
    ResponseResult inbox(Integer pageNum, Integer pageSize);

    ResponseResult unreadCount();

    ResponseResult markRead(Long id);

    ResponseResult markAllRead();

    ResponseResult delete(Long id);

    void notify(Long receiverId, Long actorId, Long articleId, Long commentId, Long rootCommentId,
                String type, String content);

    void notifyArticlePublished(Long authorId, Long articleId, String articleTitle);
}
