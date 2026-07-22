package com.yan.admin.filter;

import com.alibaba.fastjson2.JSON;
import com.yan.constants.AuthConstants;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.LoginUser;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.utils.JwtUtil;
import com.yan.utils.RedisCache;
import com.yan.utils.WebUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.List;

@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {
    private final RedisCache redisCache;

    public JwtAuthenticationTokenFilter(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        //获取请求头中的token
        String token = request.getHeader(AuthConstants.TOKEN_HEADER);
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }
        //解析获取的userid
        Claims claims;
        try {
            claims = JwtUtil.parseJWT(token);
        } catch (Exception e) {
            //token超时或不合法
            ResponseResult responseResult = ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
            WebUtils.renderString(response, HttpServletResponse.SC_UNAUTHORIZED, JSON.toJSONString(responseResult));
            return;
        }
        String userId = claims.getSubject();
        String scope = claims.get(AuthConstants.SCOPE_CLAIM, String.class);
        if (!AuthConstants.ADMIN_SCOPE.equals(scope) && !AuthConstants.BLOG_SCOPE.equals(scope)) {
            ResponseResult responseResult = ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
            WebUtils.renderString(response, HttpServletResponse.SC_UNAUTHORIZED, JSON.toJSONString(responseResult));
            return;
        }
        //从redis中获取用户信息
        LoginUser loginUser = redisCache.getCacheObject(AuthConstants.loginCacheKey(scope, userId));
        //如果获取不到
        if (Objects.isNull(loginUser)) {
            //登录过期
            ResponseResult responseResult = ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
            WebUtils.renderString(response, HttpServletResponse.SC_UNAUTHORIZED, JSON.toJSONString(responseResult));
            return;
        }
        //存入securityContentHolder
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(
                        loginUser,
                        null,
                        List.of(new SimpleGrantedAuthority(AuthConstants.authorityFor(scope)))
                );
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        filterChain.doFilter(request, response);
    }

}
