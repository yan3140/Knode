package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Menu;
import com.yan.domain.vo.MenuTreeVo;

import java.util.List;

/**
 * 菜单权限表(Menu)表服务接口
 *
 * @author makejava
 * @since 2025-12-15 15:25:15
 */
public interface MenuService extends IService<Menu> {
    List<String> selectPermById(Long id);

    ResponseResult getRouters(Long id);

    ResponseResult listMenus(String menuName, String status);

    ResponseResult menuTree();

    ResponseResult roleMenuTree(Long roleId);

    ResponseResult deleteMenu(Long id);
}
