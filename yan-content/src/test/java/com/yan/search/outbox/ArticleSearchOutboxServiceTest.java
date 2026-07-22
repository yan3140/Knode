package com.yan.search.outbox;

import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.search.metrics.ArticleSearchMetrics;
import com.yan.search.service.ArticleSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleSearchOutboxServiceTest {

    @Mock
    private ArticleSearchOutboxMapper outboxMapper;
    @Mock
    private ArticleSearchService articleSearchService;
    @Mock
    private ArticleSearchMetrics searchMetrics;

    private ArticleSearchOutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new ArticleSearchOutboxService(outboxMapper, articleSearchService,
                searchMetrics, 30, 100, 10, 300);
    }

    @Test
    void enqueuePersistsPendingEvent() {
        when(outboxMapper.insert(any(ArticleSearchOutboxEvent.class))).thenReturn(1);

        outboxService.enqueue(12L);

        ArgumentCaptor<ArticleSearchOutboxEvent> captor = ArgumentCaptor.forClass(ArticleSearchOutboxEvent.class);
        verify(outboxMapper).insert(captor.capture());
        assertThat(captor.getValue().getArticleId()).isEqualTo(12L);
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getRetryCount()).isZero();
        assertThat(captor.getValue().getNextRetryAt()).isNotNull();
    }

    @Test
    void deliversClaimedEventsInOneBatchAndMarksSuccessfulEvents() {
        ArticleSearchOutboxEvent first = event(7L, 99L);
        ArticleSearchOutboxEvent second = event(8L, 100L);
        when(outboxMapper.selectPending(100)).thenReturn(List.of(first, second));
        when(outboxMapper.claim(7L, 30)).thenReturn(1);
        when(outboxMapper.claim(8L, 30)).thenReturn(1);
        when(articleSearchService.synchronizeForOutbox(List.of(99L, 100L))).thenReturn(Set.of());

        outboxService.deliverPending();

        verify(outboxMapper).recoverExpiredClaims();
        verify(outboxMapper).markSucceeded(7L);
        verify(outboxMapper).markSucceeded(8L);
        verify(searchMetrics).recordOutbox(true, 2);
        verify(outboxMapper, never()).markForRetry(any(), anyInt(), any());
    }

    @Test
    void schedulesOnlyFailedArticleForExponentialRetry() {
        ArticleSearchOutboxEvent event = event(8L, 100L);
        event.setRetryCount(2);
        when(outboxMapper.selectPending(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(8L, 30)).thenReturn(1);
        when(articleSearchService.synchronizeForOutbox(List.of(100L))).thenReturn(Set.of(100L));

        outboxService.deliverPending();

        verify(outboxMapper).markForRetry(eq(8L), eq(40), eq("Elasticsearch synchronization failed"));
        verify(searchMetrics).recordOutbox(false, 1);
        verify(outboxMapper, never()).markSucceeded(any());
    }

    @Test
    void doesNotDeliverWhenAnotherWorkerWinsClaim() {
        ArticleSearchOutboxEvent event = event(9L, 101L);
        when(outboxMapper.selectPending(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(9L, 30)).thenReturn(0);

        outboxService.deliverPending();

        verify(articleSearchService, never()).synchronizeForOutbox(any());
        verify(outboxMapper, never()).markSucceeded(any());
    }

    private ArticleSearchOutboxEvent event(Long id, Long articleId) {
        ArticleSearchOutboxEvent event = new ArticleSearchOutboxEvent();
        event.setId(id);
        event.setArticleId(articleId);
        return event;
    }
}
