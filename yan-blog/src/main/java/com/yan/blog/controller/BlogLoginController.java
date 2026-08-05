package com.yan.blog.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.User;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.ratelimit.RateLimit;
import com.yan.ratelimit.RateLimitTarget;
import com.yan.service.BlogLoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BlogLoginController {

    @Autowired
    private BlogLoginService blogLoginService;

    @PostMapping({"/login", "/api/blog/auth/login"})
    @RateLimit(name = "blog:login:ip", target = RateLimitTarget.IP, limit = 20, windowSeconds = 300)
    @RateLimit(name = "blog:login:username", target = RateLimitTarget.PARAM, paramName = "userName",
            limit = 8, windowSeconds = 300)
    public ResponseResult login(@RequestBody User user) {
        if (!StringUtils.hasText(user.getUserName())) {
            throw new SystemException(AppHttpCodeEnum.REQUIRE_USERNAME);
        }
        return blogLoginService.login(user);

    }

    @PostMapping({"/logout", "/api/blog/auth/logout"})
    public ResponseResult logout() {
        return blogLoginService.logout();
    }
}
