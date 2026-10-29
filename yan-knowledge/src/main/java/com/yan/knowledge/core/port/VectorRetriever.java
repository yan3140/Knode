package com.yan.knowledge.core.port;

import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.retrieval.RetrievalCandidate;
import com.yan.knowledge.core.retrieval.RetrievalQuery;

import java.util.List;

public interface VectorRetriever {

    List<RetrievalCandidate> retrieve(RetrievalQuery query, KnowledgeBaseScope scope, int candidateK);
}
