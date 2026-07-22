package com.yan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.domain.entity.Role;
import org.apache.ibatis.annotations.Mapper;


/**
 * 角色信息表(Role)表数据库访问层
 *
 * @author makejava
 * @since 2025-12-15 15:34:43
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

}

