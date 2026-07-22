package com.yan.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("yan_author_publish_event")
public class AuthorPublishEvent {
    @TableId
    private Long id;
    private Long authorId;
    private Long articleId;
    private Date createTime;
}
