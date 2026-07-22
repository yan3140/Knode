package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.domain.dto.RoleDto;
import com.yan.domain.entity.Role;
import com.yan.domain.entity.RoleMenu;
import com.yan.domain.entity.UserRole;
import com.yan.domain.vo.PageVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.RoleMapper;
import com.yan.mapper.RoleMenuMapper;
import com.yan.mapper.UserRoleMapper;
import com.yan.service.RoleService;
import com.yan.utils.BeanCopyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service("roleService")
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {
    private static final Long ADMIN_ROLE_ID = 1L;

    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private RoleMenuMapper roleMenuMapper;

    @Override
    public List<String> selectRoleById(Long id) {
        if (ADMIN_ROLE_ID.equals(id)) {
            return List.of("admin");
        }
        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, id))
                .stream().map(UserRole::getRoleId).filter(Objects::nonNull).toList();
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<Role>()
                        .in(Role::getId, roleIds).select(Role::getRoleKey))
                .stream().map(Role::getRoleKey).filter(Objects::nonNull).toList();
    }

    @Override
    public ResponseResult listRoles(Integer pageNum, Integer pageSize, String roleName, String roleKey, String status) {
        Page<Role> page = page(new Page<>(normalizePage(pageNum), normalizeSize(pageSize)), new LambdaQueryWrapper<Role>()
                .like(StringUtils.hasText(roleName), Role::getRoleName, roleName)
                .like(StringUtils.hasText(roleKey), Role::getRoleKey, roleKey)
                .eq(StringUtils.hasText(status), Role::getStatus, status)
                .orderByAsc(Role::getRoleSort));
        return ResponseResult.okResult(new PageVo(page.getRecords(), page.getTotal()));
    }

    @Override
    public ResponseResult getRole(Long id) {
        Role role = getById(id);
        if (role == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        RoleDto result = BeanCopyUtils.copyBean(role, RoleDto.class);
        result.setMenuIds(roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getRoleId, id))
                .stream().map(RoleMenu::getMenuId).filter(Objects::nonNull).toList());
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult listAllRoles() {
        return ResponseResult.okResult(list(new LambdaQueryWrapper<Role>()
                .eq(Role::getStatus, "0").orderByAsc(Role::getRoleSort)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult addRole(RoleDto roleDto) {
        validateRole(roleDto);
        if (count(new LambdaQueryWrapper<Role>().eq(Role::getRoleKey, roleDto.getRoleKey())) > 0) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        Role role = BeanCopyUtils.copyBean(roleDto, Role.class);
        role.setDelFlag("0");
        save(role);
        replaceRoleMenus(role.getId(), roleDto.getMenuIds());
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateRole(RoleDto roleDto) {
        validateRole(roleDto);
        if (roleDto.getId() == null || getById(roleDto.getId()) == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        if (count(new LambdaQueryWrapper<Role>().eq(Role::getRoleKey, roleDto.getRoleKey()).ne(Role::getId, roleDto.getId())) > 0) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        updateById(BeanCopyUtils.copyBean(roleDto, Role.class));
        replaceRoleMenus(roleDto.getId(), roleDto.getMenuIds());
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult deleteRoles(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.contains(ADMIN_ROLE_ID)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.ADMIN_OPERATION_FORBIDDEN);
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>().in(RoleMenu::getRoleId, ids));
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().in(UserRole::getRoleId, ids));
        removeByIds(ids);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult updateRoleStatus(Long id, String status) {
        if (ADMIN_ROLE_ID.equals(id) || !StringUtils.hasText(status) || getById(id) == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        Role role = new Role();
        role.setId(id);
        role.setStatus(status);
        updateById(role);
        return ResponseResult.okResult();
    }

    private void replaceRoleMenus(Long roleId, List<Long> menuIds) {
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getRoleId, roleId));
        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }
        List<RoleMenu> relations = menuIds.stream().filter(Objects::nonNull).distinct().map(menuId -> {
            RoleMenu relation = new RoleMenu();
            relation.setRoleId(roleId);
            relation.setMenuId(menuId);
            return relation;
        }).toList();
        relations.forEach(roleMenuMapper::insert);
    }

    private void validateRole(RoleDto roleDto) {
        if (roleDto == null || !StringUtils.hasText(roleDto.getRoleName()) || !StringUtils.hasText(roleDto.getRoleKey())
                || roleDto.getRoleSort() == null || !StringUtils.hasText(roleDto.getStatus())) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
    }

    private long normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 100);
    }
}
