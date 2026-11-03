package com.knode.user.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.knode.user.account.AccountService;
import com.knode.user.account.UserRepository;
import com.knode.user.auth.RevocationCoordinator;
import com.knode.user.auth.UserTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserController {
    private final AccountService accounts;
    private final UserRepository users;
    private final RevocationCoordinator revocations;
    private final com.knode.user.account.LoginAttemptLimiter loginLimiter;
    public UserController(AccountService accounts,UserRepository users,RevocationCoordinator revocations,
                          com.knode.user.account.LoginAttemptLimiter loginLimiter) {
        this.accounts=accounts;this.users=users;this.revocations=revocations;this.loginLimiter=loginLimiter;
    }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Login(String userName,String password) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Registration(String userName,String nickName,String password,String email) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Profile(String nickName,String avatar,String sex) {}
    private UserTokens.Principal principal(HttpServletRequest req) { return (UserTokens.Principal)req.getAttribute(UserRequestFilter.PRINCIPAL); }
    @PostMapping("/api/blog/auth/login")
    public ApiResponse blogLogin(@RequestBody Login input,HttpServletRequest req) {
        String username=input==null?null:input.userName();
        Long id=loginLimiter.check(username,"blog",req);
        var r=accounts.login(username,input==null?null:input.password(),"blog",id);
        return ApiResponse.ok(Map.of("token",r.token(),"userInfo",r.userInfo()));
    }
    @PostMapping("/api/admin/auth/login")
    public ApiResponse adminLogin(@RequestBody Login input,HttpServletRequest req) {
        String username=input==null?null:input.userName();
        Long id=loginLimiter.check(username,"admin",req);
        var r=accounts.login(username,input==null?null:input.password(),"admin",id);
        return ApiResponse.ok(Map.of("token",r.token()));
    }
    @PostMapping("/api/blog/user/register")
    public ApiResponse register(@RequestBody Registration input) {
        accounts.register(input.userName(),input.nickName(),input.password(),input.email());return ApiResponse.ok();
    }
    @GetMapping("/api/blog/user/userInfo")
    public ApiResponse info(HttpServletRequest req) {
        return ApiResponse.ok(accounts.active(Long.parseLong(principal(req).userId()),"blog").info());
    }
    @PutMapping("/api/blog/user/userInfo")
    public ApiResponse update(@RequestBody Profile input,HttpServletRequest req) {
        accounts.update(Long.parseLong(principal(req).userId()),input.nickName(),input.avatar(),input.sex());return ApiResponse.ok();
    }
    @GetMapping("/api/admin/auth/me")
    public ApiResponse me(HttpServletRequest req) {
        long id=Long.parseLong(principal(req).userId());
        return ApiResponse.ok(Map.of("roles",users.roles(id),"permissions",users.permissions(id),"user",accounts.active(id,"admin").info()));
    }
    @GetMapping("/api/admin/auth/routers")
    public ApiResponse routers(HttpServletRequest req) {
        return ApiResponse.ok(Map.of("menus",users.routers(Long.parseLong(principal(req).userId()))));
    }
    @PostMapping({"/api/blog/auth/logout","/api/admin/auth/logout"})
    public ApiResponse logout(HttpServletRequest req) { revocations.revoke(principal(req));return ApiResponse.ok(); }
}
