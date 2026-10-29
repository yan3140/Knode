package com.yan.knowledge.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data @TableName("yan_knowledge_feedback")
public class KnowledgeFeedbackEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long queryId; private Long userId; private Integer helpful; private String comment; private Date createTime;
}
