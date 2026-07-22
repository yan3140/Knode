package com.yan.domain.vo;

import lombok.Data;

import java.util.Date;

@Data
public class NotificationVo {
    private Long id;
    private String type;
    private String content;
    private Integer isRead;
    private Date createTime;
    private Long actorId;
    private String actorNickName;
    private String actorAvatar;
    private Long articleId;
    private String articleTitle;
    private Long commentId;
    private Long rootCommentId;
}
