package com.yan.knowledge.core.port;

import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.domain.KnowledgeChunk;

import java.util.List;

public interface FullContextSource {

    List<KnowledgeChunk> load(KnowledgeBaseScope scope);
}
