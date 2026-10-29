package com.yan.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("yan_knowledge_chunk")
public class KnowledgeChunkEntity {

    @TableId
    private String id;
    private Long documentId;
    private Long documentVersionId;
    private Integer chunkIndex;
    private String title;
    private String sectionName;
    private String content;
    private String sourceUri;
    private Date createTime;
}
