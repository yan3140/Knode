package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.injector.methods.SelectById;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Menu;
import com.yan.domain.entity.Role;
import com.yan.domain.entity.RoleMenu;
import com.yan.domain.entity.UserRole;
import com.yan.domain.vo.MenuVo;
import com.yan.domain.vo.RouterVo;
import com.yan.mapper.MenuMapper;
import com.yan.mapper.RoleMenuMapper;
import com.yan.mapper.UserRoleMapper;
import com.yan.service.MenuService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 菜单权限表(Menu)表服务实现类
 *
 * @author makejava
 * @since 2025-12-15 15:25:15
 */
@Service("menuService")
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements MenuService {


    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private RoleMenuMapper roleMenuMapper;
    @Override
    public List<String> selectPermById(Long id) {
        //如果是管理员返回所有权限
        if(SecurityUtils.isAdmin()){
         LambdaQueryWrapper<Menu> wrapper = new LambdaQueryWrapper<Menu>();
         wrapper.in(Menu::getMenuType, SystemConstants.MENU,SystemConstants.BUTTON);
         wrapper.eq(Menu::getStatus,SystemConstants.ARTICLE_STATUS_NORMAL);
         List<String> permissions = list(wrapper).stream()
                 .map(Menu::getPerms)
                 .toList();
         return permissions;
        }else {
            List<Long> menuIds = getMenuIdsByUserId(id);
         LambdaQueryWrapper<Menu> wrapper2 =  new LambdaQueryWrapper<Menu>();
         wrapper2.in(Menu::getId, menuIds);
         wrapper2.in(Menu::getMenuType,SystemConstants.MENU,SystemConstants.BUTTON);
         wrapper2.eq(Menu::getStatus,SystemConstants.ARTICLE_STATUS_NORMAL);
         List<String> permissions = list(wrapper2).stream()
                 .map(Menu::getPerms)
                 .toList();
         return permissions;
        }

    }

    @Override
    public ResponseResult getRouters(Long id) {
        if(SecurityUtils.isAdmin()){
            LambdaQueryWrapper<Menu> wrapper = new LambdaQueryWrapper<Menu>();
            wrapper.in(Menu::getMenuType, SystemConstants.MENU,SystemConstants.TABLE);
            wrapper.eq(Menu::getStatus,SystemConstants.ARTICLE_STATUS_NORMAL);
            List<MenuVo> menuVos = BeanCopyUtils.copyBeanList(list(wrapper), MenuVo.class);
            List<MenuVo> menuVoList = menuVos.stream()
                    .filter(menuVo -> menuVo.getParentId().equals(0L))
                    .peek(menuVo -> menuVo.setChildren(buildChildren(menuVo.getId(), menuVos)))
                    .toList();
            return ResponseResult.okResult(new RouterVo(menuVoList));
        }else {
            //通过menuId查询根menu
            List<MenuVo> menuVos = BeanCopyUtils.copyBeanList(getMenuByUserId(id), MenuVo.class);
            List<MenuVo> menuVoList = menuVos.stream()
                    .filter(menuVo -> menuVo.getParentId().equals(0L))
                    .peek(menuVo -> menuVo.setChildren(buildChildren(menuVo.getId(), menuVos)))
                    .toList();

            //再将menuVo封装成RouterVo
            RouterVo routerVo = new RouterVo(menuVoList);
            return ResponseResult.okResult(routerVo);
        }

    }

    private List<Menu> getMenuByUserId(Long userId) {
        List<Long> menuIds = getMenuIdsByUserId(userId);
        //通过menuId查询根menu
        LambdaQueryWrapper<Menu> wrapper2 = new LambdaQueryWrapper<>();
        wrapper2.in(Menu::getId,menuIds)
                .eq(Menu::getStatus,SystemConstants.ARTICLE_STATUS_NORMAL)
                .in(Menu::getMenuType,SystemConstants.MENU,SystemConstants.TABLE)
                .orderByAsc(Menu::getOrderNum);
        List<Menu> menus = list(wrapper2);
        return menus;
    }


    private List<MenuVo> buildChildren(Long menuId,List<MenuVo> menuVos) {
        List<MenuVo> list = menuVos.stream()
                .filter(menuVo -> menuVo.getParentId().equals(menuId))
                //递归查找
                .peek(menuVo -> menuVo.setChildren(buildChildren(menuVo.getId(), menuVos)))
                .toList();
        return list;
    }
    private List<Long> getMenuIdsByUserId(Long userId) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getUserId,userId);
        UserRole userRole = userRoleMapper.selectOne(wrapper);
        if (userRole == null) { // 处理用户无角色的情况
            return Collections.emptyList();
        }
        Long roleId = userRole.getRoleId();
        LambdaQueryWrapper<RoleMenu> wrapper1 = new LambdaQueryWrapper<RoleMenu>();
        wrapper1.eq(RoleMenu::getRoleId,roleId);
        wrapper1.select(RoleMenu::getMenuId);
        List<Long> menuIds = roleMenuMapper.selectObjs(wrapper1).stream()
                .filter(Objects::nonNull)
                .map(obj->(Long)obj)
                .toList();
        return menuIds;
    }
}

