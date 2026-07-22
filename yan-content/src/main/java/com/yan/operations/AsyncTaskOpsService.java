package com.yan.operations;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.domain.entity.NotificationOutboxEvent;
import com.yan.domain.vo.PageVo;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.mapper.NotificationOutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.Map;

@Service
public class AsyncTaskOpsService {
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ArticleSearchOutboxMapper articleSearchOutboxMapper;
    private final NotificationOutboxMapper notificationOutboxMapper;

    public AsyncTaskOpsService(ArticleSearchOutboxMapper articleSearchOutboxMapper,
                               NotificationOutboxMapper notificationOutboxMapper) {
        this.articleSearchOutboxMapper = articleSearchOutboxMapper;
        this.notificationOutboxMapper = notificationOutboxMapper;
    }

    public ResponseResult summary() {
        Date now = new Date();
        return ResponseResult.okResult(Map.of(
                "search", searchSummary(now),
                "notification", notificationSummary(now)
        ));
    }

    public ResponseResult searchEvents(Integer pageNum, Integer pageSize, String status) {
        Page<ArticleSearchOutboxEvent> page = new Page<>(pageNum(pageNum), pageSize(pageSize));
        LambdaQueryWrapper<ArticleSearchOutboxEvent> wrapper = new LambdaQueryWrapper<ArticleSearchOutboxEvent>()
                .eq(StringUtils.hasText(status), ArticleSearchOutboxEvent::getStatus, status)
                .orderByDesc(ArticleSearchOutboxEvent::getUpdateTime)
                .orderByDesc(ArticleSearchOutboxEvent::getId);
        Page<ArticleSearchOutboxEvent> result = articleSearchOutboxMapper.selectPage(page, wrapper);
        return ResponseResult.okResult(new PageVo(result.getRecords(), result.getTotal()));
    }

    public ResponseResult notificationEvents(Integer pageNum, Integer pageSize, String status) {
        Page<NotificationOutboxEvent> page = new Page<>(pageNum(pageNum), pageSize(pageSize));
        LambdaQueryWrapper<NotificationOutboxEvent> wrapper = new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(StringUtils.hasText(status), NotificationOutboxEvent::getStatus, status)
                .orderByDesc(NotificationOutboxEvent::getUpdateTime)
                .orderByDesc(NotificationOutboxEvent::getId);
        Page<NotificationOutboxEvent> result = notificationOutboxMapper.selectPage(page, wrapper);
        return ResponseResult.okResult(new PageVo(result.getRecords(), result.getTotal()));
    }

    public ResponseResult replaySearchPending() {
        articleSearchOutboxMapper.recoverExpiredClaims();
        int requeued = articleSearchOutboxMapper.requeuePending();
        return ResponseResult.okResult(Map.of("requeued", requeued));
    }

    public ResponseResult replayNotificationPending() {
        notificationOutboxMapper.recoverExpiredClaims();
        int requeued = notificationOutboxMapper.requeuePending();
        return ResponseResult.okResult(Map.of("requeued", requeued));
    }

    public ResponseResult replayAllPending() {
        articleSearchOutboxMapper.recoverExpiredClaims();
        notificationOutboxMapper.recoverExpiredClaims();
        int searchRequeued = articleSearchOutboxMapper.requeuePending();
        int notificationRequeued = notificationOutboxMapper.requeuePending();
        return ResponseResult.okResult(Map.of(
                "searchRequeued", searchRequeued,
                "notificationRequeued", notificationRequeued
        ));
    }

    private Map<String, Long> searchSummary(Date now) {
        return Map.of(
                "total", articleSearchOutboxMapper.selectCount(new LambdaQueryWrapper<>()),
                "pending", countSearch(PENDING),
                "duePending", articleSearchOutboxMapper.selectCount(new LambdaQueryWrapper<ArticleSearchOutboxEvent>()
                        .eq(ArticleSearchOutboxEvent::getStatus, PENDING)
                        .le(ArticleSearchOutboxEvent::getNextRetryAt, now)),
                "processing", countSearch(PROCESSING),
                "expiredProcessing", articleSearchOutboxMapper.selectCount(new LambdaQueryWrapper<ArticleSearchOutboxEvent>()
                        .eq(ArticleSearchOutboxEvent::getStatus, PROCESSING)
                        .isNotNull(ArticleSearchOutboxEvent::getLockedUntil)
                        .lt(ArticleSearchOutboxEvent::getLockedUntil, now)),
                "succeeded", countSearch(SUCCEEDED),
                "failed", countSearch(FAILED)
        );
    }

    private Map<String, Long> notificationSummary(Date now) {
        return Map.of(
                "total", notificationOutboxMapper.selectCount(new LambdaQueryWrapper<>()),
                "pending", countNotification(PENDING),
                "duePending", notificationOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationOutboxEvent>()
                        .eq(NotificationOutboxEvent::getStatus, PENDING)
                        .le(NotificationOutboxEvent::getNextRetryAt, now)),
                "processing", countNotification(PROCESSING),
                "expiredProcessing", notificationOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationOutboxEvent>()
                        .eq(NotificationOutboxEvent::getStatus, PROCESSING)
                        .isNotNull(NotificationOutboxEvent::getLockedUntil)
                        .lt(NotificationOutboxEvent::getLockedUntil, now)),
                "succeeded", countNotification(SUCCEEDED),
                "failed", countNotification(FAILED)
        );
    }

    private Long countSearch(String status) {
        return articleSearchOutboxMapper.selectCount(new LambdaQueryWrapper<ArticleSearchOutboxEvent>()
                .eq(ArticleSearchOutboxEvent::getStatus, status));
    }

    private Long countNotification(String status) {
        return notificationOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getStatus, status));
    }

    private long pageNum(Integer value) {
        return value == null || value < 1 ? DEFAULT_PAGE_NUM : value;
    }

    private long pageSize(Integer value) {
        if (value == null || value < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(value, MAX_PAGE_SIZE);
    }
}
