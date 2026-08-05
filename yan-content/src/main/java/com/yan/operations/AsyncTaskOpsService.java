package com.yan.operations;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.domain.entity.CacheInvalidationOutboxEvent;
import com.yan.domain.entity.NotificationDeliveryOutboxEvent;
import com.yan.domain.entity.NotificationOutboxEvent;
import com.yan.domain.vo.PageVo;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.mapper.CacheInvalidationOutboxMapper;
import com.yan.mapper.NotificationDeliveryOutboxMapper;
import com.yan.mapper.NotificationOutboxMapper;
import com.yan.reliabletask.redisstream.ReliableTaskRedisStreamProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
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
    private final NotificationDeliveryOutboxMapper notificationDeliveryOutboxMapper;
    private final CacheInvalidationOutboxMapper cacheInvalidationOutboxMapper;
    private final StringRedisTemplate redisTemplate;
    private final ReliableTaskRedisStreamProperties redisStreamProperties;

    public AsyncTaskOpsService(ArticleSearchOutboxMapper articleSearchOutboxMapper,
                               NotificationOutboxMapper notificationOutboxMapper,
                               NotificationDeliveryOutboxMapper notificationDeliveryOutboxMapper,
                               CacheInvalidationOutboxMapper cacheInvalidationOutboxMapper,
                               ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                               ObjectProvider<ReliableTaskRedisStreamProperties> redisStreamPropertiesProvider) {
        this.articleSearchOutboxMapper = articleSearchOutboxMapper;
        this.notificationOutboxMapper = notificationOutboxMapper;
        this.notificationDeliveryOutboxMapper = notificationDeliveryOutboxMapper;
        this.cacheInvalidationOutboxMapper = cacheInvalidationOutboxMapper;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.redisStreamProperties = redisStreamPropertiesProvider.getIfAvailable();
    }

    public ResponseResult summary() {
        Date now = new Date();
        return ResponseResult.okResult(Map.of(
                "search", searchSummary(now),
                "notification", notificationSummary(now),
                "notificationDelivery", notificationDeliverySummary(now),
                "cacheInvalidation", cacheInvalidationSummary(now)
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
        notificationDeliveryOutboxMapper.recoverExpiredClaims();
        cacheInvalidationOutboxMapper.recoverExpiredClaims();
        int searchRequeued = articleSearchOutboxMapper.requeuePending();
        int notificationRequeued = notificationOutboxMapper.requeuePending();
        int notificationDeliveryRequeued = notificationDeliveryOutboxMapper.requeuePending();
        int cacheInvalidationRequeued = cacheInvalidationOutboxMapper.requeuePending();
        return ResponseResult.okResult(Map.of(
                "searchRequeued", searchRequeued,
                "notificationRequeued", notificationRequeued,
                "notificationDeliveryRequeued", notificationDeliveryRequeued,
                "cacheInvalidationRequeued", cacheInvalidationRequeued
        ));
    }

    private Map<String, Object> searchSummary(Date now) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", articleSearchOutboxMapper.selectCount(new LambdaQueryWrapper<>()));
        summary.put("pending", countSearch(PENDING));
        summary.put("duePending", articleSearchOutboxMapper.selectCount(new LambdaQueryWrapper<ArticleSearchOutboxEvent>()
                .eq(ArticleSearchOutboxEvent::getStatus, PENDING)
                .le(ArticleSearchOutboxEvent::getNextRetryAt, now)));
        summary.put("processing", countSearch(PROCESSING));
        summary.put("expiredProcessing", articleSearchOutboxMapper.selectCount(new LambdaQueryWrapper<ArticleSearchOutboxEvent>()
                .eq(ArticleSearchOutboxEvent::getStatus, PROCESSING)
                .isNotNull(ArticleSearchOutboxEvent::getLockedUntil)
                .lt(ArticleSearchOutboxEvent::getLockedUntil, now)));
        summary.put("succeeded", countSearch(SUCCEEDED));
        summary.put("failed", countSearch(FAILED));
        summary.put("processedAttempts", articleSearchOutboxMapper.sumProcessCount());
        summary.put("maxProcessCount", articleSearchOutboxMapper.maxProcessCount());
        summary.put("streamLength", streamLength());
        summary.put("streamPendingCount", streamPendingCount());
        return summary;
    }

    private Map<String, Object> notificationDeliverySummary(Date now) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", notificationDeliveryOutboxMapper.selectCount(new LambdaQueryWrapper<>()));
        summary.put("pending", countNotificationDelivery(PENDING));
        summary.put("duePending", notificationDeliveryOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationDeliveryOutboxEvent>()
                .eq(NotificationDeliveryOutboxEvent::getStatus, PENDING)
                .le(NotificationDeliveryOutboxEvent::getNextRetryAt, now)));
        summary.put("processing", countNotificationDelivery(PROCESSING));
        summary.put("expiredProcessing", notificationDeliveryOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationDeliveryOutboxEvent>()
                .eq(NotificationDeliveryOutboxEvent::getStatus, PROCESSING)
                .isNotNull(NotificationDeliveryOutboxEvent::getLockedUntil)
                .lt(NotificationDeliveryOutboxEvent::getLockedUntil, now)));
        summary.put("succeeded", countNotificationDelivery(SUCCEEDED));
        summary.put("failed", countNotificationDelivery(FAILED));
        return summary;
    }

    private Map<String, Object> cacheInvalidationSummary(Date now) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", cacheInvalidationOutboxMapper.selectCount(new LambdaQueryWrapper<>()));
        summary.put("pending", countCacheInvalidation(PENDING));
        summary.put("duePending", cacheInvalidationOutboxMapper.selectCount(new LambdaQueryWrapper<CacheInvalidationOutboxEvent>()
                .eq(CacheInvalidationOutboxEvent::getStatus, PENDING)
                .le(CacheInvalidationOutboxEvent::getNextRetryAt, now)));
        summary.put("processing", countCacheInvalidation(PROCESSING));
        summary.put("expiredProcessing", cacheInvalidationOutboxMapper.selectCount(new LambdaQueryWrapper<CacheInvalidationOutboxEvent>()
                .eq(CacheInvalidationOutboxEvent::getStatus, PROCESSING)
                .isNotNull(CacheInvalidationOutboxEvent::getLockedUntil)
                .lt(CacheInvalidationOutboxEvent::getLockedUntil, now)));
        summary.put("succeeded", countCacheInvalidation(SUCCEEDED));
        summary.put("failed", countCacheInvalidation(FAILED));
        summary.put("processedAttempts", cacheInvalidationOutboxMapper.sumProcessCount());
        summary.put("maxProcessCount", cacheInvalidationOutboxMapper.maxProcessCount());
        return summary;
    }

    private Map<String, Object> notificationSummary(Date now) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", notificationOutboxMapper.selectCount(new LambdaQueryWrapper<>()));
        summary.put("pending", countNotification(PENDING));
        summary.put("duePending", notificationOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getStatus, PENDING)
                .le(NotificationOutboxEvent::getNextRetryAt, now)));
        summary.put("processing", countNotification(PROCESSING));
        summary.put("expiredProcessing", notificationOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getStatus, PROCESSING)
                .isNotNull(NotificationOutboxEvent::getLockedUntil)
                .lt(NotificationOutboxEvent::getLockedUntil, now)));
        summary.put("succeeded", countNotification(SUCCEEDED));
        summary.put("failed", countNotification(FAILED));
        summary.put("processedAttempts", notificationOutboxMapper.sumProcessCount());
        summary.put("maxProcessCount", notificationOutboxMapper.maxProcessCount());
        return summary;
    }

    private Long countSearch(String status) {
        return articleSearchOutboxMapper.selectCount(new LambdaQueryWrapper<ArticleSearchOutboxEvent>()
                .eq(ArticleSearchOutboxEvent::getStatus, status));
    }

    private Long countNotification(String status) {
        return notificationOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationOutboxEvent>()
                .eq(NotificationOutboxEvent::getStatus, status));
    }

    private Long countNotificationDelivery(String status) {
        return notificationDeliveryOutboxMapper.selectCount(new LambdaQueryWrapper<NotificationDeliveryOutboxEvent>()
                .eq(NotificationDeliveryOutboxEvent::getStatus, status));
    }

    private Long countCacheInvalidation(String status) {
        return cacheInvalidationOutboxMapper.selectCount(new LambdaQueryWrapper<CacheInvalidationOutboxEvent>()
                .eq(CacheInvalidationOutboxEvent::getStatus, status));
    }

    private long streamLength() {
        if (redisTemplate == null || redisStreamProperties == null) {
            return -1L;
        }
        try {
            Long size = redisTemplate.opsForStream().size(redisStreamProperties.getStreams().getArticleSearch());
            return size == null ? -1L : size;
        } catch (RedisConnectionFailureException | IllegalStateException redisUnavailable) {
            return -1L;
        }
    }

    private long streamPendingCount() {
        if (redisTemplate == null || redisStreamProperties == null) {
            return -1L;
        }
        try {
            PendingMessagesSummary pending = redisTemplate.opsForStream().pending(
                    redisStreamProperties.getStreams().getArticleSearch(),
                    redisStreamProperties.getGroups().getArticleSearch());
            return pending == null ? -1L : pending.getTotalPendingMessages();
        } catch (RedisConnectionFailureException | IllegalStateException redisUnavailable) {
            return -1L;
        }
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
