package com.yan.knowledge.task;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yan.knowledge.domain.entity.KnowledgeIndexOutboxEvent;
import com.yan.knowledge.mapper.KnowledgeIndexOutboxMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KnowledgeIndexOutboxServiceTest {

    @Test
    void claimsPendingEventsBeforeProcessingThem() {
        KnowledgeIndexOutboxMapper mapper = Mockito.mock(KnowledgeIndexOutboxMapper.class);
        KnowledgeIndexOutboxProcessor processor = Mockito.mock(KnowledgeIndexOutboxProcessor.class);
        KnowledgeIndexOutboxEvent event = new KnowledgeIndexOutboxEvent();
        event.setId(9L);
        when(mapper.selectPending(100)).thenReturn(List.of(event));
        when(mapper.claim(9L, 60)).thenReturn(1);

        new KnowledgeIndexOutboxService(mapper, processor, new KnowledgeOutboxProperties(), List.of())
                .deliverPending();

        verify(mapper).recoverExpiredClaims();
        verify(processor).process(List.of(event));
    }

    @Test
    void relaysClaimedEventToRedisStreamWhenEnabled() {
        KnowledgeIndexOutboxMapper mapper = Mockito.mock(KnowledgeIndexOutboxMapper.class);
        KnowledgeIndexOutboxProcessor processor = Mockito.mock(KnowledgeIndexOutboxProcessor.class);
        KnowledgeIndexOutboxMessageGateway gateway = Mockito.mock(KnowledgeIndexOutboxMessageGateway.class);
        KnowledgeIndexOutboxEvent event = new KnowledgeIndexOutboxEvent();
        event.setId(9L);
        when(mapper.selectPending(100)).thenReturn(List.of(event));
        when(mapper.claim(9L, 60)).thenReturn(1);
        KnowledgeOutboxProperties properties = new KnowledgeOutboxProperties();
        properties.getStream().setEnabled(true);

        new KnowledgeIndexOutboxService(mapper, processor, properties, List.of(gateway)).deliverPending();

        verify(gateway).publish(event);
        verify(processor, org.mockito.Mockito.never()).process(anyList());
    }
}
