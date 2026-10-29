package com.yan.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    KnowledgeDocumentEntity selectBySource(@Param("sourceType") String sourceType,
                                           @Param("sourceId") String sourceId);

    int publishVersion(@Param("documentId") Long documentId, @Param("versionId") Long versionId);

    int clearCurrentVersion(@Param("documentId") Long documentId, @Param("versionId") Long versionId);
}
