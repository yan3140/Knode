package com.yan.operations;

import com.yan.domain.ResponseResult;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.mapper.NotificationOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

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

    private AsyncTaskOpsService service;

    @BeforeEach
    void setUp() {
        service = new AsyncTaskOpsService(articleSearchOutboxMapper, notificationOutboxMapper);
    }

    @Test
    void replayAllPendingShouldRecoverExpiredClaimsBeforeRequeue() {
        when(articleSearchOutboxMapper.requeuePending()).thenReturn(3);
        when(notificationOutboxMapper.requeuePending()).thenReturn(5);

        ResponseResult result = service.replayAllPending();

        Map<String, Integer> data = (Map<String, Integer>) result.getData();
        assertThat(data).containsEntry("searchRequeued", 3);
        assertThat(data).containsEntry("notificationRequeued", 5);
        verify(articleSearchOutboxMapper).recoverExpiredClaims();
        verify(notificationOutboxMapper).recoverExpiredClaims();
    }
}
