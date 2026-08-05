package com.yan.notification;

import com.yan.domain.entity.NotificationDeliveryOutboxEvent;
import com.yan.domain.entity.UserNotification;
import com.yan.mapper.NotificationDeliveryOutboxMapper;
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

@Service
public class NotificationDeliveryOutboxService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryOutboxService.class);

    private final NotificationDeliveryOutboxMapper outboxMapper;
    private final UserNotificationMapper notificationMapper;
    private final NotificationDeliveryOutboxMessageGateway messageGateway;
    private final int pollBatchSize;
    private final int leaseSeconds;
    private final boolean streamEnabled;
    private final ReliableTaskPolicy taskPolicy;

    public NotificationDeliveryOutboxService(NotificationDeliveryOutboxMapper outboxMapper,
                                             UserNotificationMapper notificationMapper,
                                             List<NotificationDeliveryOutboxMessageGateway> messageGateways,
                                             @Value("${blog.notification.delivery-outbox.poll-batch-size:50}") int pollBatchSize,
                                             @Value("${blog.notification.delivery-outbox.lease-seconds:30}") int leaseSeconds,
                                             @Value("${blog.notification.delivery-outbox.retry-delay-seconds:10}") int retryDelaySeconds,
                                             @Value("${blog.notification.delivery-outbox.max-retry-delay-seconds:300}") int maxRetryDelaySeconds,
                                             @Value("${blog.notification.delivery-outbox.max-retry:8}") int maxRetry,
                                             @Value("${blog.notification.delivery-outbox.stream.enabled:false}") boolean streamEnabled) {
        this.outboxMapper = outboxMapper;
        this.notificationMapper = notificationMapper;
        this.messageGateway = messageGateways.isEmpty() ? null : messageGateways.get(0);
        this.pollBatchSize = positive(pollBatchSize, "poll-batch-size");
        this.leaseSeconds = positive(leaseSeconds, "lease-seconds");
        this.streamEnabled = streamEnabled;
        this.taskPolicy = new ReliableTaskPolicy(maxRetry, retryDelaySeconds, maxRetryDelaySeconds);
    }

    public void enqueue(Long receiverId, Long actorId, Long articleId, Long commentId, Long rootCommentId,
                        String type, String content) {
        if (receiverId == null || actorId == null || receiverId.equals(actorId) || type == null) {
            return;
        }
        String safeContent = content == null || content.length() <= 500 ? content : content.substring(0, 500);
        String taskKey = ReliableTaskKeys.directNotification(type, receiverId, actorId, articleId, commentId, rootCommentId);
        if (outboxMapper.enqueue(taskKey, receiverId, actorId, articleId, commentId, rootCommentId,
                type, safeContent, taskPolicy.maxRetry()) < 1) {
            throw new IllegalStateException("Unable to persist notification delivery outbox event");
        }
    }

    @Scheduled(fixedDelayString = "${blog.notification.delivery-outbox.poll-delay-ms:500}")
    public void deliverPending() {
        outboxMapper.recoverExpiredClaims();
        List<NotificationDeliveryOutboxEvent> claimed = new ArrayList<>();
        for (NotificationDeliveryOutboxEvent event : outboxMapper.selectPending(pollBatchSize)) {
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
            LOGGER.warn("event=notification_delivery_stream_gateway_missing action=direct_fallback claimed={}",
                    claimed.size());
        }
        claimed.forEach(this::deliver);
    }

    void deliver(NotificationDeliveryOutboxEvent event) {
        try {
            UserNotification notification = new UserNotification();
            notification.setDedupeKey(event.getTaskKey());
            notification.setReceiverId(event.getReceiverId());
            notification.setActorId(event.getActorId());
            notification.setArticleId(event.getArticleId());
            notification.setCommentId(event.getCommentId());
            notification.setRootCommentId(event.getRootCommentId());
            notification.setType(event.getType());
            notification.setContent(event.getContent());
            notification.setIsRead(0);
            int inserted = notificationMapper.insertIgnore(notification);
            if (inserted < 1 && notificationMapper.countByDedupeKey(event.getTaskKey()) < 1) {
                throw new IllegalStateException("Notification insert ignored without existing dedupe key");
            }
            outboxMapper.markSucceeded(event.getId());
        } catch (RuntimeException error) {
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            markForRetry(event, reason, error);
        }
    }

    void markForRetry(NotificationDeliveryOutboxEvent event, String reason) {
        markForRetry(event, reason, null);
    }

    private void relayToRedisStream(List<NotificationDeliveryOutboxEvent> claimed) {
        int published = 0;
        int failed = 0;
        for (NotificationDeliveryOutboxEvent event : claimed) {
            try {
                messageGateway.publish(event);
                published++;
            } catch (RuntimeException publishFailure) {
                markForRetry(event, publishFailure.getMessage(), publishFailure);
                failed++;
            }
        }
        LOGGER.info("event=notification_delivery_relay_completed claimed={} published={} failed={}",
                claimed.size(), published, failed);
    }

    private void markForRetry(NotificationDeliveryOutboxEvent event, String reason, RuntimeException error) {
        int delaySeconds = taskPolicy.nextRetryDelaySeconds(event.getRetryCount());
        outboxMapper.markForRetry(event.getId(), delaySeconds, taskPolicy.safeError(reason));
        String nextStatus = taskPolicy.willDeadLetter(event.getRetryCount())
                ? ReliableTaskStatus.FAILED : ReliableTaskStatus.PENDING;
        LOGGER.warn("event=notification_delivery_retry_scheduled outboxId={} type={} nextStatus={} retryDelaySeconds={}",
                event.getId(), event.getType(), nextStatus, delaySeconds, error);
    }

    private int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("notification delivery " + name + " must be positive");
        }
        return value;
    }
}
