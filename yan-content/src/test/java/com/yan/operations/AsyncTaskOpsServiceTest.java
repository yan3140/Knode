package com.yan.operations;

import com.yan.domain.ResponseResult;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.mapper.CacheInvalidationOutboxMapper;
import com.yan.mapper.NotificationDeliveryOutboxMapper;
import com.yan.mapper.NotificationOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncTaskOpsServiceTest {
    @Mock
    private ArticleSearchOutboxMapper articleSearchOutboxMapper;
    @Mock
    private NotificationOutboxMapper notificationOutboxMapper;
    @Mock
    private NotificationDeliveryOutboxMapper notificationDeliveryOutboxMapper;
    @Mock
    private CacheInvalidationOutboxMapper cacheInvalidationOutboxMapper;
    @Mock
    private ObjectProvider redisTemplateProvider;
    @Mock
    private ObjectProvider redisStreamPropertiesProvider;

    private AsyncTaskOpsService service;

    @BeforeEach
    void setUp() {
        service = new AsyncTaskOpsService(articleSearchOutboxMapper, notificationOutboxMapper,
                notificationDeliveryOutboxMapper, cacheInvalidationOutboxMapper,
                redisTemplateProvider, redisStreamPropertiesProvider);
    }

    @Test
    void replayAllPendingShouldRecoverExpiredClaimsBeforeRequeue() {
        when(articleSearchOutboxMapper.requeuePending()).thenReturn(3);
        when(notificationOutboxMapper.requeuePending()).thenReturn(5);
        when(notificationDeliveryOutboxMapper.requeuePending()).thenReturn(7);
        when(cacheInvalidationOutboxMapper.requeuePending()).thenReturn(11);

        ResponseResult result = service.replayAllPending();

        Map<String, Integer> data = (Map<String, Integer>) result.getData();
        assertThat(data).containsEntry("searchRequeued", 3);
        assertThat(data).containsEntry("notificationRequeued", 5);
        assertThat(data).containsEntry("notificationDeliveryRequeued", 7);
        assertThat(data).containsEntry("cacheInvalidationRequeued", 11);
        verify(articleSearchOutboxMapper).recoverExpiredClaims();
        verify(notificationOutboxMapper).recoverExpiredClaims();
        verify(notificationDeliveryOutboxMapper).recoverExpiredClaims();
        verify(cacheInvalidationOutboxMapper).recoverExpiredClaims();
    }
}
