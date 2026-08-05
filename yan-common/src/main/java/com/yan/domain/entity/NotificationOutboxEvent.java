package com.yan.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("yan_notification_outbox")
public class NotificationOutboxEvent {
    @TableId
    private Long id;
    private Long articleId;
    private Long authorId;
    private String taskKey;
    private Long cursorFollowerId;
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
