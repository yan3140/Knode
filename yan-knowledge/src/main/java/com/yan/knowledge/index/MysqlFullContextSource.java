package com.yan.knowledge.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.domain.KnowledgeChunk;
import com.yan.knowledge.core.port.FullContextSource;
import com.yan.knowledge.domain.entity.KnowledgeChunkEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import com.yan.knowledge.mapper.KnowledgeChunkMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MysqlFullContextSource implements FullContextSource {
    private final KnowledgeDocumentMapper documents; private final KnowledgeChunkMapper chunks;
    public MysqlFullContextSource(KnowledgeDocumentMapper documents, KnowledgeChunkMapper chunks) { this.documents=documents; this.chunks=chunks; }
    @Override public List<KnowledgeChunk> load(KnowledgeBaseScope scope) {
        if (scope.documentIds().isEmpty()) return List.of();
        Set<Long> documentIds=scope.documentIds().stream().map(Long::valueOf).collect(java.util.stream.Collectors.toSet());
        Set<Long> versions=documents.selectByIds(documentIds).stream().map(KnowledgeDocumentEntity::getCurrentVersionId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (versions.isEmpty()) return List.of();
        return chunks.selectList(new LambdaQueryWrapper<KnowledgeChunkEntity>().in(KnowledgeChunkEntity::getDocumentVersionId, versions).orderByAsc(KnowledgeChunkEntity::getDocumentVersionId).orderByAsc(KnowledgeChunkEntity::getChunkIndex))
                .stream().map(c -> new KnowledgeChunk(c.getId(), c.getDocumentId().toString(), c.getDocumentVersionId().toString(), c.getChunkIndex(), c.getContent(), c.getTitle(), c.getSectionName(), c.getSourceUri(), Map.of())).toList();
    }
}
