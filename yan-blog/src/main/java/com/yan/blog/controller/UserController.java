package com.yan.blog.controller;

import com.yan.annotation.SystemLog;
import com.yan.domain.ResponseResult;

import com.yan.domain.entity.User;
import com.yan.service.impl.UserServiceImpl;
import com.yan.service.UserProfileService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/user", "/api/blog/user"})
public class UserController {

    private final UserServiceImpl userService;
    private final UserProfileService userProfileService;

    public UserController(UserServiceImpl userService, UserProfileService userProfileService) {
        this.userService = userService;
        this.userProfileService = userProfileService;
    }

    @GetMapping("/userInfo")
    public ResponseResult userInfo() {
        return userService.userInfo();
    }

    @SystemLog(businessName = "更新用户信息")
    @PutMapping("/userInfo")
    public ResponseResult updateUserInfo(@RequestBody User user) {
        return userService.updateUserInfo(user);
    }

    @PostMapping("/register")
    public ResponseResult register(@RequestBody User user) {
        return userService.register(user);
    }

    @GetMapping("/profile/{id}")
    public ResponseResult profile(@PathVariable Long id, Integer pageNum, Integer pageSize) {
        return userProfileService.profile(id, pageNum, pageSize);
    }
}
