package com.yan.notification;

import com.yan.domain.entity.NotificationOutboxEvent;
import com.yan.mapper.NotificationOutboxMapper;
import com.yan.mapper.UserNotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationFanoutOutboxServiceTest {
    private NotificationOutboxMapper outboxMapper;
    private UserNotificationMapper notificationMapper;
    private NotificationFanoutOutboxMessageGateway messageGateway;
    private NotificationFanoutOutboxService service;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(NotificationOutboxMapper.class);
        notificationMapper = mock(UserNotificationMapper.class);
        messageGateway = mock(NotificationFanoutOutboxMessageGateway.class);
        service = new NotificationFanoutOutboxService(outboxMapper, notificationMapper,
                List.of(), 500, 20, 30, 10_000, 10, 300, 8, false);
    }

    @Test
    void enqueueShouldUseStableTaskKeyForSinglePublishNotificationTask() {
        when(outboxMapper.enqueue(100L, 20L, "ARTICLE_PUBLISH_NOTIFY:20:100", 8)).thenReturn(1);

        service.enqueue(100L, 20L);

        verify(outboxMapper).enqueue(100L, 20L, "ARTICLE_PUBLISH_NOTIFY:20:100", 8);
    }

    @Test
    void deliversClaimedEventsDirectlyWhenStreamDisabled() {
        NotificationOutboxEvent event = event(7L, 100L, 20L);
        when(outboxMapper.selectPending(20)).thenReturn(List.of(event));
        when(outboxMapper.claim(7L, 30)).thenReturn(1);
        when(outboxMapper.countFollowers(20L)).thenReturn(0L);

        service.deliverPending();

        verify(outboxMapper).recoverExpiredClaims();
        verify(outboxMapper).markProcessingStarted(7L);
        verify(outboxMapper).markSucceeded(7L);
        verify(messageGateway, never()).publish(any());
    }

    @Test
    void relaysClaimedEventsToRedisStreamWhenStreamEnabled() {
        NotificationFanoutOutboxService streamService = new NotificationFanoutOutboxService(outboxMapper,
                notificationMapper, List.of(messageGateway), 500, 20, 30, 10_000, 10, 300, 8, true);
        NotificationOutboxEvent event = event(8L, 101L, 21L);
        when(outboxMapper.selectPending(20)).thenReturn(List.of(event));
        when(outboxMapper.claim(8L, 30)).thenReturn(1);

        streamService.deliverPending();

        verify(outboxMapper).markProcessingStarted(8L);
        verify(messageGateway).publish(event);
        verify(outboxMapper, never()).markSucceeded(any());
    }

    private NotificationOutboxEvent event(Long id, Long articleId, Long authorId) {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setId(id);
        event.setArticleId(articleId);
        event.setAuthorId(authorId);
        event.setRetryCount(0);
        return event;
    }
}
