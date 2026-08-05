package com.yan.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("yan_cache_invalidation_outbox")
public class CacheInvalidationOutboxEvent {
    @TableId
    private Long id;
    private String taskKey;
    private String cacheKey;
    private Integer patternFlag;
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private Date nextRetryAt;
    private Date lockedUntil;
    private String lastError;
    private Date deadLetterAt;
    private Integer processCount;
    private Date lastProcessAt;
    private Date createTime;
    private Date updateTime;
}
