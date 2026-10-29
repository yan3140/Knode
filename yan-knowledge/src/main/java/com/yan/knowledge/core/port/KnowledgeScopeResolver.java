package com.yan.knowledge.core.port;

import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.retrieval.RetrievalQuery;

public interface KnowledgeScopeResolver {

    KnowledgeBaseScope resolve(RetrievalQuery query);
}
