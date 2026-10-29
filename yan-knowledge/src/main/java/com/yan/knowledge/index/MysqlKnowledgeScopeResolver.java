package com.yan.knowledge.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.port.KnowledgeScopeResolver;
import com.yan.knowledge.core.retrieval.RetrievalQuery;
import com.yan.knowledge.domain.entity.KnowledgeBaseDocumentEntity;
import com.yan.knowledge.mapper.KnowledgeBaseDocumentMapper;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MysqlKnowledgeScopeResolver implements KnowledgeScopeResolver {
    private final KnowledgeBaseDocumentMapper mapper;
    public MysqlKnowledgeScopeResolver(KnowledgeBaseDocumentMapper mapper) { this.mapper = mapper; }
    @Override public KnowledgeBaseScope resolve(RetrievalQuery query) {
        Set<String> ids = mapper.selectList(new LambdaQueryWrapper<KnowledgeBaseDocumentEntity>()
                .eq(KnowledgeBaseDocumentEntity::getKnowledgeBaseId, Long.valueOf(query.knowledgeBaseId())))
                .stream().map(row -> row.getDocumentId().toString()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new KnowledgeBaseScope(query.knowledgeBaseId(), ids);
    }
}
