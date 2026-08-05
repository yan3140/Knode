package com.yan.notification;

import com.yan.domain.entity.NotificationOutboxEvent;
import com.yan.domain.entity.UserNotification;
import com.yan.mapper.NotificationOutboxMapper;
import com.yan.mapper.UserNotificationMapper;
import com.yan.reliabletask.ReliableTaskKeys;
import com.yan.reliabletask.ReliableTaskPolicy;
import com.yan.reliabletask.ReliableTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Delivers article-published notifications outside the article write transaction. */
@Service
public class NotificationFanoutOutboxService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationFanoutOutboxService.class);

    private final NotificationOutboxMapper outboxMapper;
    private final UserNotificationMapper notificationMapper;
    private final NotificationFanoutOutboxMessageGateway messageGateway;
    private final int batchSize;
    private final int pollBatchSize;
    private final int leaseSeconds;
    private final int largeAuthorThreshold;
    private final boolean streamEnabled;
    private final ReliableTaskPolicy taskPolicy;

    public NotificationFanoutOutboxService(NotificationOutboxMapper outboxMapper,
                                           UserNotificationMapper notificationMapper,
                                           List<NotificationFanoutOutboxMessageGateway> messageGateways,
                                           @Value("${blog.notification.fanout.batch-size:500}") int batchSize,
                                           @Value("${blog.notification.outbox.poll-batch-size:20}") int pollBatchSize,
                                           @Value("${blog.notification.outbox.lease-seconds:30}") int leaseSeconds,
                                           @Value("${blog.notification.large-author-threshold:10000}") int largeAuthorThreshold,
                                           @Value("${blog.notification.outbox.retry-delay-seconds:10}") int retryDelaySeconds,
                                           @Value("${blog.notification.outbox.max-retry-delay-seconds:300}") int maxRetryDelaySeconds,
                                           @Value("${blog.notification.outbox.max-retry:8}") int maxRetry,
                                           @Value("${blog.notification.outbox.stream.enabled:false}") boolean streamEnabled) {
        this.outboxMapper = outboxMapper;
        this.notificationMapper = notificationMapper;
        this.messageGateway = messageGateways.isEmpty() ? null : messageGateways.get(0);
        this.batchSize = positive(batchSize, "batch-size");
        this.pollBatchSize = positive(pollBatchSize, "poll-batch-size");
        this.leaseSeconds = positive(leaseSeconds, "lease-seconds");
        this.largeAuthorThreshold = positive(largeAuthorThreshold, "large-author-threshold");
        this.streamEnabled = streamEnabled;
        this.taskPolicy = new ReliableTaskPolicy(maxRetry, retryDelaySeconds, maxRetryDelaySeconds);
    }

    public void enqueue(Long articleId, Long authorId) {
        if (articleId == null || authorId == null) return;
        String taskKey = ReliableTaskKeys.articlePublishNotification(authorId, articleId);
        if (outboxMapper.enqueue(articleId, authorId, taskKey, taskPolicy.maxRetry()) < 1) {
            throw new IllegalStateException("Unable to persist notification outbox event");
        }
    }

    @Scheduled(fixedDelayString = "${blog.notification.outbox.poll-delay-ms:500}")
    public void deliverPending() {
        outboxMapper.recoverExpiredClaims();
        List<NotificationOutboxEvent> claimed = new ArrayList<>();
        for (NotificationOutboxEvent event : outboxMapper.selectPending(pollBatchSize)) {
            if (outboxMapper.claim(event.getId(), leaseSeconds) == 1) {
                outboxMapper.markProcessingStarted(event.getId());
                claimed.add(event);
            }
        }
        if (claimed.isEmpty()) {
            return;
        }
        if (streamEnabled && messageGateway != null) {
            relayToRedisStream(claimed);
            return;
        }
        if (streamEnabled) {
            LOGGER.warn("event=notification_fanout_stream_gateway_missing action=direct_fallback claimed={}",
                    claimed.size());
        }
        claimed.forEach(this::deliver);
    }

    void deliver(NotificationOutboxEvent event) {
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
                notification.setDedupeKey("ARTICLE_PUBLISHED:" + event.getArticleId() + ":" + followerId);
                notification.setIsRead(0);
                return notification;
            }).toList();
            notificationMapper.insertBatchIgnore(notifications);
            outboxMapper.continueFromCursor(event.getId(), followerIds.get(followerIds.size() - 1));
            LOGGER.info("event=notification_fanout_batch outboxId={} articleId={} delivered={} cursor={}",
                    event.getId(), event.getArticleId(), notifications.size(), followerIds.get(followerIds.size() - 1));
        } catch (RuntimeException error) {
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            markForRetry(event, reason, error);
        }
    }

    void markForRetry(NotificationOutboxEvent event, String reason) {
        markForRetry(event, reason, null);
    }

    private void relayToRedisStream(List<NotificationOutboxEvent> claimed) {
        int published = 0;
        int failed = 0;
        for (NotificationOutboxEvent event : claimed) {
            try {
                messageGateway.publish(event);
                published++;
            } catch (RuntimeException publishFailure) {
                markForRetry(event, publishFailure.getMessage(), publishFailure);
                failed++;
            }
        }
        LOGGER.info("event=notification_fanout_relay_completed claimed={} published={} failed={}",
                claimed.size(), published, failed);
    }

    private void markForRetry(NotificationOutboxEvent event, String reason, RuntimeException error) {
        int delaySeconds = taskPolicy.nextRetryDelaySeconds(event.getRetryCount());
        outboxMapper.markForRetry(event.getId(), delaySeconds, taskPolicy.safeError(reason));
        String nextStatus = taskPolicy.willDeadLetter(event.getRetryCount())
                ? ReliableTaskStatus.FAILED : ReliableTaskStatus.PENDING;
        LOGGER.warn("event=notification_fanout_retry_scheduled outboxId={} articleId={} nextStatus={} retryDelaySeconds={}",
                event.getId(), event.getArticleId(), nextStatus, delaySeconds, error);
    }

    private int positive(int value, String name) { if (value < 1) throw new IllegalArgumentException("notification " + name + " must be positive"); return value; }
}
