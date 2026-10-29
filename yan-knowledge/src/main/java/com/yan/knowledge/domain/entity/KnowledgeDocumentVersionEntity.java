package com.yan.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("yan_knowledge_document_version")
public class KnowledgeDocumentVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Integer versionNo;
    private String contentHash;
    private String title;
    private String content;
    private String sourceUri;
    private String status;
    private Integer keywordEnabled;
    private Integer vectorEnabled;
    private String keywordAvailability;
    private String vectorAvailability;
    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDimensions;
    private String chunkingPolicyVersion;
    private String keywordSchemaVersion;
    private Date createTime;
}
