package com.yan.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("yan_notification_delivery_outbox")
public class NotificationDeliveryOutboxEvent {
    @TableId
    private Long id;
    private String taskKey;
    private Long receiverId;
    private Long actorId;
    private Long articleId;
    private Long commentId;
    private Long rootCommentId;
    private String type;
    private String content;
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
