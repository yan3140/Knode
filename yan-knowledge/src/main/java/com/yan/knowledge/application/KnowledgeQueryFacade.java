package com.yan.knowledge.application;

import com.yan.knowledge.core.retrieval.AdaptiveRetriever;
import com.yan.knowledge.core.retrieval.ContextBudget;
import com.yan.knowledge.core.retrieval.RetrievalMode;
import com.yan.knowledge.core.retrieval.RetrievalQuery;
import com.yan.knowledge.core.retrieval.RetrievalResult;
import com.yan.knowledge.domain.entity.KnowledgeFeedbackEntity;
import com.yan.knowledge.domain.entity.KnowledgeQueryEntity;
import com.yan.knowledge.mapper.KnowledgeFeedbackMapper;
import com.yan.knowledge.mapper.KnowledgeQueryMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeQueryFacade {
    private final KnowledgeBaseFacade bases; private final AdaptiveRetriever retriever; private final KnowledgeQueryMapper queries; private final KnowledgeFeedbackMapper feedback;
    public KnowledgeQueryFacade(KnowledgeBaseFacade bases, AdaptiveRetriever retriever, KnowledgeQueryMapper queries, KnowledgeFeedbackMapper feedback) { this.bases=bases; this.retriever=retriever; this.queries=queries; this.feedback=feedback; }
    @Transactional(rollbackFor=Exception.class)
    public QueryResponse query(Long userId, Long baseId, String text, RetrievalMode mode) {
        return query(userId, baseId, text, mode, 10, 30);
    }
    @Transactional(rollbackFor=Exception.class)
    public QueryResponse query(Long userId, Long baseId, String text, RetrievalMode mode, int topK, int candidateK) {
        if (baseId == null || !baseId.equals(1L)) bases.ownedPrivate(userId, baseId);
        RetrievalQuery request=new RetrievalQuery(baseId.toString(), text, Map.of(), Map.of(), mode, topK, candidateK, new ContextBudget(12_000,2_000,500), UUID.randomUUID().toString());
        RetrievalResult result=retriever.retrieve(request);
        KnowledgeQueryEntity audit=new KnowledgeQueryEntity(); audit.setKnowledgeBaseId(baseId); audit.setUserId(userId); audit.setQueryText(text); audit.setRequestedMode(mode.name()); audit.setSelectedMode(result.selectedMode().name()); audit.setDegraded(result.degraded()?1:0); audit.setDegradationReasons(result.degradationReasons().toString()); audit.setTotalElapsedMillis(result.totalElapsedMillis()); audit.setTraceId(result.traceId());
        if(queries.insert(audit)!=1) throw new IllegalStateException("Unable to write knowledge query audit");
        return new QueryResponse(audit.getId(), result);
    }
    @Transactional(rollbackFor=Exception.class)
    public void feedback(Long userId, Long queryId, boolean helpful, String comment) { KnowledgeQueryEntity query=queries.selectById(queryId); if(query==null || !userId.equals(query.getUserId())) throw new IllegalArgumentException("Query is not owned by current user"); KnowledgeFeedbackEntity entity=new KnowledgeFeedbackEntity(); entity.setQueryId(queryId); entity.setUserId(userId); entity.setHelpful(helpful?1:0); entity.setComment(comment); if(feedback.insert(entity)!=1) throw new IllegalStateException("Unable to write knowledge feedback"); }
    public record QueryResponse(Long queryId, RetrievalResult result) {}
}
