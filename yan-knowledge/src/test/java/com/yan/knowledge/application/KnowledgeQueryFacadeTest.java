package com.yan.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.yan.knowledge.core.retrieval.AdaptiveRetriever;
import com.yan.knowledge.core.retrieval.RetrievalMode;
import com.yan.knowledge.core.retrieval.RetrievalQuery;
import com.yan.knowledge.core.retrieval.RetrievalResult;
import com.yan.knowledge.domain.entity.KnowledgeQueryEntity;
import com.yan.knowledge.mapper.KnowledgeFeedbackMapper;
import com.yan.knowledge.mapper.KnowledgeQueryMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeQueryFacadeTest {
    @Test void auditsActualRetrievalResult() {
        KnowledgeBaseFacade bases=mock(KnowledgeBaseFacade.class); AdaptiveRetriever retriever=mock(AdaptiveRetriever.class); KnowledgeQueryMapper queries=mock(KnowledgeQueryMapper.class); KnowledgeFeedbackMapper feedback=mock(KnowledgeFeedbackMapper.class);
        org.mockito.Mockito.when(retriever.retrieve(any())).thenReturn(new RetrievalResult(RetrievalMode.AUTO,RetrievalMode.KEYWORD,List.of(),true,List.of(),12,"trace"));
        doAnswer(i->{i.getArgument(0,KnowledgeQueryEntity.class).setId(9L);return 1;}).when(queries).insert(any(KnowledgeQueryEntity.class));
        KnowledgeQueryFacade facade=new KnowledgeQueryFacade(bases,retriever,queries,feedback);
        assertThat(facade.query(3L,5L,"线程池",RetrievalMode.AUTO).queryId()).isEqualTo(9L);
        verify(bases).ownedPrivate(3L,5L);
    }
    @Test void forwardsControlledRetrievalBudget() {
        KnowledgeBaseFacade bases=mock(KnowledgeBaseFacade.class); AdaptiveRetriever retriever=mock(AdaptiveRetriever.class); KnowledgeQueryMapper queries=mock(KnowledgeQueryMapper.class); KnowledgeFeedbackMapper feedback=mock(KnowledgeFeedbackMapper.class);
        org.mockito.Mockito.when(retriever.retrieve(any())).thenReturn(new RetrievalResult(RetrievalMode.AUTO,RetrievalMode.HYBRID,List.of(),false,List.of(),12,"trace"));
        doAnswer(i->{i.getArgument(0,KnowledgeQueryEntity.class).setId(10L);return 1;}).when(queries).insert(any(KnowledgeQueryEntity.class));
        KnowledgeQueryFacade facade=new KnowledgeQueryFacade(bases,retriever,queries,feedback);

        facade.query(3L,5L,"B+ 树",RetrievalMode.AUTO,5,15);

        org.mockito.ArgumentCaptor<RetrievalQuery> request=org.mockito.ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(retriever).retrieve(request.capture());
        assertThat(request.getValue().topK()).isEqualTo(5);
        assertThat(request.getValue().candidateK()).isEqualTo(15);
    }
    @Test void rejectsFeedbackForAnotherUsersQuery() {
        KnowledgeBaseFacade bases=mock(KnowledgeBaseFacade.class); KnowledgeQueryMapper queries=mock(KnowledgeQueryMapper.class); KnowledgeQueryEntity query=new KnowledgeQueryEntity();query.setUserId(4L);org.mockito.Mockito.when(queries.selectById(9L)).thenReturn(query);
        KnowledgeQueryFacade facade=new KnowledgeQueryFacade(bases,mock(AdaptiveRetriever.class),queries,mock(KnowledgeFeedbackMapper.class));
        assertThatThrownBy(()->facade.feedback(3L,9L,true,"ok")).isInstanceOf(IllegalArgumentException.class);
    }
}
