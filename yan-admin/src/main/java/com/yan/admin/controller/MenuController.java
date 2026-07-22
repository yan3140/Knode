package com.yan.admin.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Menu;
import com.yan.service.MenuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/system/menu", "/api/admin/system/menu"})
public class MenuController {
    @Autowired
    private MenuService menuService;

    @GetMapping("/list")
    public ResponseResult list(String menuName, String status) {
        return menuService.listMenus(menuName, status);
    }

    @GetMapping("/treeselect")
    public ResponseResult treeselect() {
        return menuService.menuTree();
    }

    @GetMapping("/roleMenuTreeselect/{roleId}")
    public ResponseResult roleMenuTreeselect(@PathVariable Long roleId) {
        return menuService.roleMenuTree(roleId);
    }

    @GetMapping("/{id}")
    public ResponseResult get(@PathVariable Long id) {
        return ResponseResult.okResult(menuService.getById(id));
    }

    @PostMapping
    public ResponseResult add(@RequestBody Menu menu) {
        menuService.save(menu);
        return ResponseResult.okResult();
    }

    @PutMapping
    public ResponseResult update(@RequestBody Menu menu) {
        menuService.updateById(menu);
        return ResponseResult.okResult();
    }

    @DeleteMapping("/{id}")
    public ResponseResult delete(@PathVariable Long id) {
        return menuService.deleteMenu(id);
    }
}
