package com.yan.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.LoginUser;
import com.yan.domain.entity.User;
import com.yan.domain.vo.AdminUserInfoVo;
import com.yan.domain.vo.UserInfoVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.service.LoginService;
import com.yan.service.MenuService;
import com.yan.service.RoleService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class AdminLoginController {

    @Autowired
    private LoginService loginService;

    @Autowired
    private MenuService menuService;

    @Autowired
    private RoleService roleService;

    @PostMapping("/user/login")
    public ResponseResult login(@RequestBody User user) {
        if (!StringUtils.hasText(user.getUserName())) {
            throw new SystemException(AppHttpCodeEnum.REQUIRE_USERNAME);
        }
        return loginService.login(user);
    }

    @PostMapping("/user/logout")
    public ResponseResult logout() {
        return loginService.logout();
    }

    @GetMapping("/getInfo")
    public ResponseResult getInfo() {
        //查询当前用户id
        LoginUser loginUser = SecurityUtils.getLoginUser();
        //通过id查询权限信息
        List<String> permissions = menuService.selectPermById(loginUser.getUser().getId());
        //查询用户信息
        List<String> roles = roleService.selectRoleById(loginUser.getUser().getId());
        UserInfoVo userInfoVo = BeanCopyUtils.copyBean(loginUser.getUser(), UserInfoVo.class);
        AdminUserInfoVo adminUserInfoVo = new AdminUserInfoVo(roles, permissions, userInfoVo);
        return ResponseResult.okResult(adminUserInfoVo);
    }

    @GetMapping("/getRouters")
    public ResponseResult getRouters() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        return menuService.getRouters(loginUser.getUser().getId());
    }
}
