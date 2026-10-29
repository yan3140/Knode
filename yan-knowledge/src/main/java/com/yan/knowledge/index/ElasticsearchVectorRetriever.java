package com.yan.knowledge.index;

import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.port.VectorRetriever;
import com.yan.knowledge.core.retrieval.RetrievalCandidate;
import com.yan.knowledge.core.retrieval.RetrievalQuery;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchVectorRetriever implements VectorRetriever {
    private final ElasticsearchKnowledgeRetriever delegate;
    public ElasticsearchVectorRetriever(ElasticsearchKnowledgeRetriever delegate) { this.delegate = delegate; }
    @Override public List<RetrievalCandidate> retrieve(RetrievalQuery query, KnowledgeBaseScope scope, int candidateK) { return delegate.vector(query, scope, candidateK); }
}
