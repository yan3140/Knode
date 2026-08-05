package com.yan.search.outbox;

import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.mapper.ArticleSearchOutboxMapper;
import com.yan.search.metrics.ArticleSearchMetrics;
import com.yan.search.service.ArticleSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleSearchOutboxProcessorTest {

    @Mock
    private ArticleSearchOutboxMapper outboxMapper;
    @Mock
    private ArticleSearchService articleSearchService;
    @Mock
    private ArticleSearchMetrics searchMetrics;

    private ArticleSearchOutboxProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ArticleSearchOutboxProcessor(outboxMapper, articleSearchService,
                searchMetrics, 10, 300, 8);
    }

    @Test
    void marksSuccessfulEventsAfterBulkSynchronization() {
        ArticleSearchOutboxEvent first = event(7L, 99L);
        ArticleSearchOutboxEvent second = event(8L, 100L);
        when(articleSearchService.synchronizeForOutbox(List.of(99L, 100L))).thenReturn(Set.of());

        processor.process(List.of(first, second));

        verify(outboxMapper).markSucceeded(7L);
        verify(outboxMapper).markSucceeded(8L);
        verify(searchMetrics).recordOutbox(true, 2);
        verify(outboxMapper, never()).markForRetry(any(), anyInt(), any());
    }

    @Test
    void schedulesOnlyFailedArticleForExponentialRetry() {
        ArticleSearchOutboxEvent event = event(8L, 100L);
        event.setRetryCount(2);
        when(articleSearchService.synchronizeForOutbox(List.of(100L))).thenReturn(Set.of(100L));

        processor.process(List.of(event));

        verify(outboxMapper).markForRetry(eq(8L), eq(40), eq("Elasticsearch synchronization failed"));
        verify(searchMetrics).recordOutbox(false, 1);
        verify(outboxMapper, never()).markSucceeded(any());
    }

    private ArticleSearchOutboxEvent event(Long id, Long articleId) {
        ArticleSearchOutboxEvent event = new ArticleSearchOutboxEvent();
        event.setId(id);
        event.setArticleId(articleId);
        event.setRetryCount(0);
        return event;
    }
}
