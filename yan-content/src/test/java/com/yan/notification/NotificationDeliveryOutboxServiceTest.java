package com.yan.notification;

import com.yan.domain.entity.NotificationDeliveryOutboxEvent;
import com.yan.mapper.NotificationDeliveryOutboxMapper;
import com.yan.mapper.UserNotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryOutboxServiceTest {
    private NotificationDeliveryOutboxMapper outboxMapper;
    private UserNotificationMapper notificationMapper;
    private NotificationDeliveryOutboxMessageGateway messageGateway;
    private NotificationDeliveryOutboxService service;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(NotificationDeliveryOutboxMapper.class);
        notificationMapper = mock(UserNotificationMapper.class);
        messageGateway = mock(NotificationDeliveryOutboxMessageGateway.class);
        service = new NotificationDeliveryOutboxService(outboxMapper, notificationMapper,
                List.of(), 50, 30, 10, 300, 8, false);
    }

    @Test
    void enqueueShouldPersistStableDirectNotificationTask() {
        when(outboxMapper.enqueue("COMMENT:30", 10L, 20L,
                100L, 30L, null, "COMMENT", "hello", 8)).thenReturn(1);

        service.enqueue(10L, 20L, 100L, 30L, null, "COMMENT", "hello");

        verify(outboxMapper).enqueue("COMMENT:30", 10L, 20L,
                100L, 30L, null, "COMMENT", "hello", 8);
    }

    @Test
    void deliversClaimedEventsDirectlyWhenStreamDisabled() {
        NotificationDeliveryOutboxEvent event = event(7L);
        when(outboxMapper.selectPending(50)).thenReturn(List.of(event));
        when(outboxMapper.claim(7L, 30)).thenReturn(1);
        when(notificationMapper.insertIgnore(any())).thenReturn(1);

        service.deliverPending();

        verify(outboxMapper).recoverExpiredClaims();
        verify(outboxMapper).markProcessingStarted(7L);
        verify(notificationMapper).insertIgnore(any());
        verify(outboxMapper).markSucceeded(7L);
        verify(messageGateway, never()).publish(any());
    }

    @Test
    void retriesWhenInsertIgnoreDropsNotificationWithoutExistingDedupe() {
        NotificationDeliveryOutboxEvent event = event(9L);
        when(outboxMapper.selectPending(50)).thenReturn(List.of(event));
        when(outboxMapper.claim(9L, 30)).thenReturn(1);
        when(notificationMapper.insertIgnore(any())).thenReturn(0);
        when(notificationMapper.countByDedupeKey("COMMENT:30")).thenReturn(0);

        service.deliverPending();

        verify(outboxMapper).markForRetry(9L, 10, "Notification insert ignored without existing dedupe key");
        verify(outboxMapper, never()).markSucceeded(9L);
    }

    @Test
    void treatsExistingDedupeAsIdempotentSuccessWhenInsertIgnored() {
        NotificationDeliveryOutboxEvent event = event(10L);
        when(outboxMapper.selectPending(50)).thenReturn(List.of(event));
        when(outboxMapper.claim(10L, 30)).thenReturn(1);
        when(notificationMapper.insertIgnore(any())).thenReturn(0);
        when(notificationMapper.countByDedupeKey("COMMENT:30")).thenReturn(1);

        service.deliverPending();

        verify(outboxMapper).markSucceeded(10L);
        verify(outboxMapper, never()).markForRetry(anyLong(), anyInt(), anyString());
    }

    @Test
    void relaysClaimedEventsToRedisStreamWhenStreamEnabled() {
        NotificationDeliveryOutboxService streamService = new NotificationDeliveryOutboxService(outboxMapper,
                notificationMapper, List.of(messageGateway), 50, 30, 10, 300, 8, true);
        NotificationDeliveryOutboxEvent event = event(8L);
        when(outboxMapper.selectPending(50)).thenReturn(List.of(event));
        when(outboxMapper.claim(8L, 30)).thenReturn(1);

        streamService.deliverPending();

        verify(outboxMapper).markProcessingStarted(8L);
        verify(messageGateway).publish(event);
        verify(notificationMapper, never()).insertIgnore(any());
    }

    private NotificationDeliveryOutboxEvent event(Long id) {
        NotificationDeliveryOutboxEvent event = new NotificationDeliveryOutboxEvent();
        event.setId(id);
        event.setTaskKey("COMMENT:30");
        event.setReceiverId(10L);
        event.setActorId(20L);
        event.setArticleId(100L);
        event.setCommentId(30L);
        event.setType("COMMENT");
        event.setContent("hello");
        event.setRetryCount(0);
        return event;
    }
}
