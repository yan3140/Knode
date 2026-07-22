package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.entity.Role;
import com.yan.domain.ResponseResult;
import com.yan.domain.dto.RoleDto;

import java.util.List;

/**
 * 角色信息表(Role)表服务接口
 *
 * @author makejava
 * @since 2025-12-15 15:34:45
 */
public interface RoleService extends IService<Role> {
    List<String> selectRoleById(Long id);

    ResponseResult listRoles(Integer pageNum, Integer pageSize, String roleName, String roleKey, String status);

    ResponseResult getRole(Long id);

    ResponseResult listAllRoles();

    ResponseResult addRole(RoleDto roleDto);

    ResponseResult updateRole(RoleDto roleDto);

    ResponseResult deleteRoles(List<Long> ids);

    ResponseResult updateRoleStatus(Long id, String status);
}
