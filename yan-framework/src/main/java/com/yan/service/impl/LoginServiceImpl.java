package com.yan.service.impl;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.LoginUser;
import com.yan.domain.entity.User;
import com.yan.service.LoginService;
import com.yan.utils.JwtUtil;
import com.yan.utils.RedisCache;
import com.yan.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class LoginServiceImpl implements LoginService {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private RedisCache redisCache;

    @Override
    public ResponseResult login(User user) {
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(user.getUserName(), user.getPassword());
        Authentication authenticate = authenticationManager.authenticate(authenticationToken);
        //判断是否认证通过
        if (Objects.isNull(authenticate)) {
            throw new RuntimeException("用户名或密码错误");
        }
        //获取userid生成token
        LoginUser loginUser = (LoginUser) authenticate.getPrincipal();
        String token = JwtUtil.createJWT(loginUser.getUser().getId().toString());
        Map<String, String> map = new HashMap<>();
        map.put("token", token);
        //把用户信息存入redis
        redisCache.setCacheObject("adminlogin:" + loginUser.getUser().getId(), loginUser);
        //把token封装返回
        return ResponseResult.okResult(map);
    }

    @Override
    public ResponseResult logout() {
        Long id = SecurityUtils.getLoginUser().getUser().getId();
        redisCache.deleteObject("adminlogin:" + id);
        return ResponseResult.okResult();
    }
}
