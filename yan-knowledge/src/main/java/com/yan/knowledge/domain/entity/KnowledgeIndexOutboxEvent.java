package com.yan.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("yan_knowledge_index_outbox")
public class KnowledgeIndexOutboxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long documentVersionId;
    private String operation;
    private String taskKey;
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private Date nextRetryAt;
    private Date lockedUntil;
    private String lastError;
    private Date deadLetterAt;
    private Integer processCount;
    private Date lastProcessAt;
    private Integer rerunRequested;
    private Date createTime;
    private Date updateTime;
}
