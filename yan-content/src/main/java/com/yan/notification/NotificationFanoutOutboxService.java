package com.yan.notification;

import com.yan.domain.entity.NotificationOutboxEvent;
import com.yan.domain.entity.UserNotification;
import com.yan.mapper.NotificationOutboxMapper;
import com.yan.mapper.UserNotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/** Delivers article-published notifications outside the article write transaction. */
@Service
public class NotificationFanoutOutboxService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationFanoutOutboxService.class);
    private static final String PENDING = "PENDING";

    private final NotificationOutboxMapper outboxMapper;
    private final UserNotificationMapper notificationMapper;
    private final int batchSize;
    private final int pollBatchSize;
    private final int leaseSeconds;
    private final int largeAuthorThreshold;

    public NotificationFanoutOutboxService(NotificationOutboxMapper outboxMapper,
                                           UserNotificationMapper notificationMapper,
                                           @Value("${blog.notification.fanout.batch-size:500}") int batchSize,
                                           @Value("${blog.notification.outbox.poll-batch-size:20}") int pollBatchSize,
                                           @Value("${blog.notification.outbox.lease-seconds:30}") int leaseSeconds,
                                           @Value("${blog.notification.large-author-threshold:10000}") int largeAuthorThreshold) {
        this.outboxMapper = outboxMapper;
        this.notificationMapper = notificationMapper;
        this.batchSize = positive(batchSize, "batch-size");
        this.pollBatchSize = positive(pollBatchSize, "poll-batch-size");
        this.leaseSeconds = positive(leaseSeconds, "lease-seconds");
        this.largeAuthorThreshold = positive(largeAuthorThreshold, "large-author-threshold");
    }

    public void enqueue(Long articleId, Long authorId) {
        if (articleId == null || authorId == null) return;
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setArticleId(articleId);
        event.setAuthorId(authorId);
        event.setCursorFollowerId(0L);
        event.setStatus(PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(new Date());
        if (outboxMapper.insert(event) != 1) throw new IllegalStateException("Unable to persist notification outbox event");
    }

    @Scheduled(fixedDelayString = "${blog.notification.outbox.poll-delay-ms:500}")
    public void deliverPending() {
        outboxMapper.recoverExpiredClaims();
        for (NotificationOutboxEvent event : outboxMapper.selectPending(pollBatchSize)) {
            if (outboxMapper.claim(event.getId(), leaseSeconds) == 1) deliver(event);
        }
    }

    private void deliver(NotificationOutboxEvent event) {
        try {
            if ((event.getCursorFollowerId() == null || event.getCursorFollowerId() == 0L)
                    && outboxMapper.countFollowers(event.getAuthorId()) >= largeAuthorThreshold) {
                outboxMapper.insertAuthorPublishEvent(event.getAuthorId(), event.getArticleId());
                outboxMapper.markSucceeded(event.getId());
                LOGGER.info("event=notification_author_stream_created outboxId={} articleId={} authorId={}", event.getId(), event.getArticleId(), event.getAuthorId());
                return;
            }
            long cursor = event.getCursorFollowerId() == null ? 0L : event.getCursorFollowerId();
            List<Long> followerIds = outboxMapper.selectFollowerIdsAfter(event.getAuthorId(), cursor, batchSize);
            if (followerIds.isEmpty()) {
                outboxMapper.markSucceeded(event.getId());
                return;
            }
            List<UserNotification> notifications = followerIds.stream().map(followerId -> {
                UserNotification notification = new UserNotification();
                notification.setReceiverId(followerId);
                notification.setActorId(event.getAuthorId());
                notification.setArticleId(event.getArticleId());
                notification.setType("ARTICLE_PUBLISHED");
                notification.setIsRead(0);
                return notification;
            }).toList();
            notificationMapper.insertBatchIgnore(notifications);
            outboxMapper.continueFromCursor(event.getId(), followerIds.get(followerIds.size() - 1));
            LOGGER.info("event=notification_fanout_batch outboxId={} articleId={} delivered={} cursor={}",
                    event.getId(), event.getArticleId(), notifications.size(), followerIds.get(followerIds.size() - 1));
        } catch (RuntimeException error) {
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            outboxMapper.markForRetry(event.getId(), retryDelay(event.getRetryCount()), reason.substring(0, Math.min(1000, reason.length())));
            LOGGER.warn("event=notification_fanout_retry outboxId={} articleId={}", event.getId(), event.getArticleId(), error);
        }
    }

    private int retryDelay(Integer retryCount) { return Math.min(300, 10 * (1 << Math.min(5, retryCount == null ? 0 : retryCount))); }
    private int positive(int value, String name) { if (value < 1) throw new IllegalArgumentException("notification " + name + " must be positive"); return value; }
}
