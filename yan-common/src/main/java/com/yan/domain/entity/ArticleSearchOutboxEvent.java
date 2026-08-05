package com.yan.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("yan_article_search_outbox")
public class ArticleSearchOutboxEvent {
    @TableId
    private Long id;
    private Long articleId;
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
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
