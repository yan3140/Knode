package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.entity.Role;
import com.yan.domain.entity.UserRole;
import com.yan.mapper.RoleMapper;
import com.yan.mapper.RoleMenuMapper;
import com.yan.mapper.UserRoleMapper;
import com.yan.service.RoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 角色信息表(Role)表服务实现类
 *
 * @author makejava
 * @since 2025-12-15 15:34:45
 */
@Service("roleService")
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {

    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Override
    public List<String> selectRoleById(Long id) {
        if(id==1L){
            List<String> list=new ArrayList<>();
            list.add("admin");
            return list;
        }else{
            LambdaQueryWrapper<UserRole> wrapper1 = new LambdaQueryWrapper<UserRole>();
            wrapper1.eq(UserRole::getUserId,id);
            wrapper1.select(UserRole::getRoleId);
            List<Long> roleIds = userRoleMapper.selectObjs(wrapper1);
            if(roleIds==null){
                return Collections.emptyList();
            }
            LambdaQueryWrapper<Role>  wrapper2 = new LambdaQueryWrapper<>();
            wrapper2.in(Role::getId,roleIds);
            wrapper2.select(Role::getRoleKey);
            return roleMapper.selectObjs(wrapper2);
        }
    }
}

