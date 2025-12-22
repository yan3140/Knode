package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.entity.Role;

import java.util.List;

/**
 * 角色信息表(Role)表服务接口
 *
 * @author makejava
 * @since 2025-12-15 15:34:45
 */
public interface RoleService extends IService<Role> {
    List<String> selectRoleById(Long id);
}
