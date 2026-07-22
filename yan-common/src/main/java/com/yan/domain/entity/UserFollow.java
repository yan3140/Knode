package com.yan.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 用户关注关系。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("yan_user_follow")
public class UserFollow {
    @TableId
    private Long id;
    private Long followerId;
    private Long followeeId;
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
