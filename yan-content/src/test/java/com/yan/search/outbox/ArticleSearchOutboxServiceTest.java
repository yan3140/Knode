package com.yan.search.outbox;

import com.yan.domain.entity.ArticleSearchOutboxEvent;
import com.yan.mapper.ArticleSearchOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleSearchOutboxServiceTest {

    @Mock
    private ArticleSearchOutboxMapper outboxMapper;
    @Mock
    private ArticleSearchOutboxProcessor processor;
    @Mock
    private ArticleSearchOutboxMessageGateway messageGateway;

    private ArticleSearchOutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new ArticleSearchOutboxService(outboxMapper, processor,
                List.of(), 30, 100, 10, 300, 8, false);
    }

    @Test
    void enqueuePersistsPendingEvent() {
        when(outboxMapper.enqueue(12L, "ARTICLE_SEARCH:12", 8)).thenReturn(1);

        outboxService.enqueue(12L);

        verify(outboxMapper).enqueue(12L, "ARTICLE_SEARCH:12", 8);
    }

    @Test
    void deliversClaimedEventsByDirectProcessorWhenStreamDisabled() {
        ArticleSearchOutboxEvent first = event(7L, 99L);
        ArticleSearchOutboxEvent second = event(8L, 100L);
        when(outboxMapper.selectPending(100)).thenReturn(List.of(first, second));
        when(outboxMapper.claim(7L, 30)).thenReturn(1);
        when(outboxMapper.claim(8L, 30)).thenReturn(1);

        outboxService.deliverPending();

        verify(outboxMapper).recoverExpiredClaims();
        verify(processor).process(List.of(first, second));
        verify(messageGateway, never()).publish(any());
    }

    @Test
    void relaysClaimedEventsToRedisStreamWhenStreamEnabled() {
        ArticleSearchOutboxService streamService = new ArticleSearchOutboxService(outboxMapper, processor,
                List.of(messageGateway), 30, 100, 10, 300, 8, true);
        ArticleSearchOutboxEvent first = event(7L, 99L);
        ArticleSearchOutboxEvent second = event(8L, 100L);
        when(outboxMapper.selectPending(100)).thenReturn(List.of(first, second));
        when(outboxMapper.claim(7L, 30)).thenReturn(1);
        when(outboxMapper.claim(8L, 30)).thenReturn(1);

        streamService.deliverPending();

        verify(messageGateway).publish(first);
        verify(messageGateway).publish(second);
        verify(processor, never()).process(any());
    }

    @Test
    void doesNotDeliverWhenAnotherWorkerWinsClaim() {
        ArticleSearchOutboxEvent event = event(9L, 101L);
        when(outboxMapper.selectPending(100)).thenReturn(List.of(event));
        when(outboxMapper.claim(9L, 30)).thenReturn(0);

        outboxService.deliverPending();

        verify(processor, never()).process(any());
        verify(messageGateway, never()).publish(any());
    }

    private ArticleSearchOutboxEvent event(Long id, Long articleId) {
        ArticleSearchOutboxEvent event = new ArticleSearchOutboxEvent();
        event.setId(id);
        event.setArticleId(articleId);
        return event;
    }
}
