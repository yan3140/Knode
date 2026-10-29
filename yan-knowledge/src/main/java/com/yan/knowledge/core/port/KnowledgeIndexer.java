package com.yan.knowledge.core.port;

import com.yan.knowledge.core.domain.IndexTask;
import com.yan.knowledge.core.domain.KnowledgeChunk;
import com.yan.knowledge.core.domain.KnowledgeDocumentVersion;

import java.util.List;

public interface KnowledgeIndexer {

    void index(KnowledgeDocumentVersion version, List<KnowledgeChunk> chunks, IndexTask task);

    void delete(KnowledgeDocumentVersion version, IndexTask task);
}
