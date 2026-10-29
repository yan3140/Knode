package com.yan.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data @TableName("yan_knowledge_query")
public class KnowledgeQueryEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long knowledgeBaseId; private Long userId; private String queryText; private String requestedMode; private String selectedMode;
    private Integer degraded; private String degradationReasons; private Long totalElapsedMillis; private String traceId; private Date createTime;
}
