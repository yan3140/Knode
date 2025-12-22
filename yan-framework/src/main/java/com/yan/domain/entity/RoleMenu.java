package com.yan.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_role_menu")
public class RoleMenu {
    @TableField("role_id")
    private Long roleId;
    @TableField("menu_id")
    private Long menuId;
}
