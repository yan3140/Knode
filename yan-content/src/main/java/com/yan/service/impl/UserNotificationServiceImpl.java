package com.yan.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.UserNotification;
import com.yan.domain.vo.NotificationVo;
import com.yan.domain.vo.PageVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.UserNotificationMapper;
import com.yan.mapper.UserFollowMapper;
import com.yan.service.UserNotificationService;
import com.yan.utils.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class UserNotificationServiceImpl implements UserNotificationService {
    private final UserNotificationMapper userNotificationMapper;

    @Autowired
    private UserFollowMapper userFollowMapper;

    public UserNotificationServiceImpl(UserNotificationMapper userNotificationMapper) {
        this.userNotificationMapper = userNotificationMapper;
    }

    @Override
    public ResponseResult inbox(Integer pageNum, Integer pageSize) {
        Long receiverId = SecurityUtils.getUserId();
        Page<NotificationVo> page = userNotificationMapper.selectInbox(
                new Page<>(normalizePage(pageNum), normalizeSize(pageSize)), receiverId);
        return ResponseResult.okResult(new PageVo(page.getRecords(), page.getTotal()));
    }

    @Override
    public ResponseResult unreadCount() {
        return ResponseResult.okResult(Map.of("unreadCount", userNotificationMapper.countUnread(SecurityUtils.getUserId())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult markRead(Long id) {
        if (id == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        userNotificationMapper.markRead(id, SecurityUtils.getUserId());
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult markAllRead() {
        userNotificationMapper.markAllRead(SecurityUtils.getUserId());
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult delete(Long id) {
        if (id == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        userNotificationMapper.deleteByIdAndReceiver(id, SecurityUtils.getUserId());
        return ResponseResult.okResult();
    }

    @Override
    public void notify(Long receiverId, Long actorId, Long articleId, Long commentId, Long rootCommentId,
                       String type, String content) {
        if (receiverId == null || actorId == null || receiverId.equals(actorId)) {
            return;
        }
        UserNotification notification = new UserNotification();
        notification.setReceiverId(receiverId);
        notification.setActorId(actorId);
        notification.setArticleId(articleId);
        notification.setCommentId(commentId);
        notification.setRootCommentId(rootCommentId);
        notification.setType(type);
        notification.setContent(content);
        notification.setIsRead(0);
        userNotificationMapper.insert(notification);
    }

    @Override
    public void notifyArticlePublished(Long authorId, Long articleId, String articleTitle) {
        if (authorId == null || articleId == null || userFollowMapper == null) {
            return;
        }
        List<UserNotification> notifications = userFollowMapper.selectFollowerIds(authorId).stream()
                .filter(Objects::nonNull)
                .filter(followerId -> !followerId.equals(authorId))
                .map(followerId -> {
                    UserNotification notification = new UserNotification();
                    notification.setReceiverId(followerId);
                    notification.setActorId(authorId);
                    notification.setArticleId(articleId);
                    notification.setType("ARTICLE_PUBLISHED");
                    notification.setContent(null);
                    notification.setIsRead(0);
                    return notification;
                })
                .toList();
        if (!notifications.isEmpty()) {
            userNotificationMapper.insertBatch(notifications);
        }
    }

    private long normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 100);
    }
}
