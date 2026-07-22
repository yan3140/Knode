package com.yan.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 用户站内通知收件箱记录。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("yan_user_notification")
public class UserNotification {
    @TableId
    private Long id;
    private Long receiverId;
    private Long actorId;
    private Long articleId;
    private Long commentId;
    private Long rootCommentId;
    private String type;
    private String content;
    private Integer isRead;
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
