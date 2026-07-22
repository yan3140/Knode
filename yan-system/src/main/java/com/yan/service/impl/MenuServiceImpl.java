package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Menu;
import com.yan.domain.entity.RoleMenu;
import com.yan.domain.entity.UserRole;
import com.yan.domain.vo.MenuTreeVo;
import com.yan.domain.vo.MenuVo;
import com.yan.domain.vo.RoleMenuTreeVo;
import com.yan.domain.vo.RouterVo;
import com.yan.mapper.MenuMapper;
import com.yan.mapper.RoleMenuMapper;
import com.yan.mapper.UserRoleMapper;
import com.yan.service.MenuService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service("menuService")
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements MenuService {

    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private RoleMenuMapper roleMenuMapper;

    @Override
    public List<String> selectPermById(Long id) {
        LambdaQueryWrapper<Menu> wrapper = new LambdaQueryWrapper<Menu>()
                .in(Menu::getMenuType, SystemConstants.MENU, SystemConstants.BUTTON)
                .eq(Menu::getStatus, SystemConstants.ARTICLE_STATUS_NORMAL);
        if (!SecurityUtils.isAdmin()) {
            List<Long> menuIds = getMenuIdsByUserId(id);
            if (menuIds.isEmpty()) {
                return Collections.emptyList();
            }
            wrapper.in(Menu::getId, menuIds);
        }
        return list(wrapper).stream().map(Menu::getPerms).filter(Objects::nonNull).toList();
    }

    @Override
    public ResponseResult getRouters(Long id) {
        List<Menu> menus;
        if (SecurityUtils.isAdmin()) {
            menus = list(new LambdaQueryWrapper<Menu>()
                    .in(Menu::getMenuType, SystemConstants.MENU, SystemConstants.TABLE)
                    .eq(Menu::getStatus, SystemConstants.ARTICLE_STATUS_NORMAL)
                    .orderByAsc(Menu::getOrderNum));
        } else {
            menus = getMenuByUserId(id);
        }
        List<MenuVo> menuVos = BeanCopyUtils.copyBeanList(menus, MenuVo.class);
        List<MenuVo> roots = menuVos.stream()
                .filter(menu -> Long.valueOf(0L).equals(menu.getParentId()))
                .peek(menu -> menu.setChildren(buildRouterChildren(menu.getId(), menuVos)))
                .toList();
        return ResponseResult.okResult(new RouterVo(roots));
    }

    @Override
    public ResponseResult listMenus(String menuName, String status) {
        LambdaQueryWrapper<Menu> wrapper = new LambdaQueryWrapper<Menu>()
                .like(StringUtils.hasText(menuName), Menu::getMenuName, menuName)
                .eq(StringUtils.hasText(status), Menu::getStatus, status)
                .orderByAsc(Menu::getParentId)
                .orderByAsc(Menu::getOrderNum);
        return ResponseResult.okResult(list(wrapper));
    }

    @Override
    public ResponseResult menuTree() {
        return ResponseResult.okResult(buildMenuTree(list(new LambdaQueryWrapper<Menu>()
                .orderByAsc(Menu::getOrderNum))));
    }

    @Override
    public ResponseResult roleMenuTree(Long roleId) {
        List<Long> checkedKeys = roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenu>()
                        .eq(RoleMenu::getRoleId, roleId))
                .stream().map(RoleMenu::getMenuId).filter(Objects::nonNull).toList();
        List<MenuTreeVo> menus = buildMenuTree(list(new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getOrderNum)));
        return ResponseResult.okResult(new RoleMenuTreeVo(menus, checkedKeys));
    }

    @Override
    public ResponseResult deleteMenu(Long id) {
        if (id == null || count(new LambdaQueryWrapper<Menu>().eq(Menu::getParentId, id)) > 0) {
            return ResponseResult.errorResult(com.yan.enums.AppHttpCodeEnum.PARAM_ERROR);
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getMenuId, id));
        removeById(id);
        return ResponseResult.okResult();
    }

    private List<Menu> getMenuByUserId(Long userId) {
        List<Long> menuIds = getMenuIdsByUserId(userId);
        if (menuIds.isEmpty()) {
            return Collections.emptyList();
        }
        return list(new LambdaQueryWrapper<Menu>()
                .in(Menu::getId, menuIds)
                .eq(Menu::getStatus, SystemConstants.ARTICLE_STATUS_NORMAL)
                .in(Menu::getMenuType, SystemConstants.MENU, SystemConstants.TABLE)
                .orderByAsc(Menu::getOrderNum));
    }

    private List<Long> getMenuIdsByUserId(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId))
                .stream().map(UserRole::getRoleId).filter(Objects::nonNull).toList();
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        return roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenu>()
                        .in(RoleMenu::getRoleId, roleIds))
                .stream().map(RoleMenu::getMenuId).filter(Objects::nonNull).distinct().toList();
    }

    private List<MenuVo> buildRouterChildren(Long parentId, List<MenuVo> menus) {
        return menus.stream()
                .filter(menu -> parentId.equals(menu.getParentId()))
                .peek(menu -> menu.setChildren(buildRouterChildren(menu.getId(), menus)))
                .toList();
    }

    private List<MenuTreeVo> buildMenuTree(List<Menu> menus) {
        List<MenuTreeVo> nodes = menus.stream()
                .map(menu -> {
                    MenuTreeVo node = new MenuTreeVo();
                    node.setId(menu.getId());
                    node.setLabel(menu.getMenuName());
                    return node;
                })
                .toList();
        return buildTreeChildren(0L, menus, nodes);
    }

    private List<MenuTreeVo> buildTreeChildren(Long parentId, List<Menu> menus, List<MenuTreeVo> nodes) {
        return menus.stream()
                .filter(menu -> parentId.equals(menu.getParentId()))
                .map(menu -> {
                    MenuTreeVo node = nodes.stream().filter(item -> item.getId().equals(menu.getId())).findFirst().orElseThrow();
                    node.setChildren(buildTreeChildren(menu.getId(), menus, nodes));
                    return node;
                }).toList();
    }
}
