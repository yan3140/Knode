package com.knode.user.admin;

import com.knode.user.auth.RevocationCoordinator;
import com.knode.user.auth.UserTokens;
import com.knode.user.web.ApiResponse;
import com.knode.user.web.UserRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.knode.user.admin.AdminDtos.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminUserService users;
    private final AdminRoleService roles;
    private final AdminMenuService menus;
    private final AdminSupport security;
    private final RevocationCoordinator revocations;
    public AdminController(AdminUserService users,AdminRoleService roles,AdminMenuService menus,AdminSupport security,RevocationCoordinator revocations) {
        this.users=users;this.roles=roles;this.menus=menus;this.security=security;this.revocations=revocations;
    }
    private long actor(HttpServletRequest req) { return Long.parseLong(((UserTokens.Principal)req.getAttribute(UserRequestFilter.PRINCIPAL)).userId()); }
    @GetMapping("/system/user/list") public ApiResponse users(HttpServletRequest req,Integer pageNum,Integer pageSize,String userName,String phonenumber,String status) {
        return ApiResponse.ok(users.list(actor(req),pageNum,pageSize,userName,phonenumber,status));
    }
    @GetMapping("/system/user/{id}") public ApiResponse user(HttpServletRequest req,@PathVariable long id) { return ApiResponse.ok(users.detail(actor(req),id)); }
    @PostMapping("/system/user") public ApiResponse addUser(HttpServletRequest req,@RequestBody UserInput input) { users.add(actor(req),input);return ApiResponse.ok(); }
    @PutMapping("/system/user") public ApiResponse updateUser(HttpServletRequest req,@RequestBody UserInput input) { users.update(actor(req),input);return ApiResponse.ok(); }
    @DeleteMapping("/system/user/{ids}") public ApiResponse deleteUsers(HttpServletRequest req,@PathVariable List<Long> ids) { users.delete(actor(req),ids);return ApiResponse.ok(); }
    @PutMapping("/system/user/changeStatus") public ApiResponse userStatus(HttpServletRequest req,@RequestBody StatusInput input) { users.status(actor(req),input.id(),input.status());return ApiResponse.ok(); }
    @PutMapping("/system/user/resetPwd") public ApiResponse password(HttpServletRequest req,@RequestBody PasswordInput input) { users.password(actor(req),input);return ApiResponse.ok(); }

    @GetMapping("/system/role/list") public ApiResponse roles(HttpServletRequest req,Integer pageNum,Integer pageSize,String roleName,String roleKey,String status) {
        return ApiResponse.ok(roles.list(actor(req),pageNum,pageSize,roleName,roleKey,status));
    }
    @GetMapping("/system/role/listAllRole") public ApiResponse allRoles(HttpServletRequest req) { return ApiResponse.ok(roles.all(actor(req))); }
    @GetMapping("/system/role/{id}") public ApiResponse role(HttpServletRequest req,@PathVariable long id) { return ApiResponse.ok(roles.detail(actor(req),id)); }
    @PostMapping("/system/role") public ApiResponse addRole(HttpServletRequest req,@RequestBody RoleInput input) { roles.add(actor(req),input);return ApiResponse.ok(); }
    @PutMapping("/system/role") public ApiResponse updateRole(HttpServletRequest req,@RequestBody RoleInput input) { roles.update(actor(req),input);return ApiResponse.ok(); }
    @DeleteMapping("/system/role/{ids}") public ApiResponse deleteRoles(HttpServletRequest req,@PathVariable List<Long> ids) { roles.delete(actor(req),ids);return ApiResponse.ok(); }
    @PutMapping("/system/role/changeStatus") public ApiResponse roleStatus(HttpServletRequest req,@RequestBody StatusInput input) { roles.status(actor(req),input.id(),input.status());return ApiResponse.ok(); }

    @GetMapping("/system/menu/list") public ApiResponse menus(HttpServletRequest req,String menuName,String status) { return ApiResponse.ok(menus.list(actor(req),menuName,status)); }
    @GetMapping("/system/menu/treeselect") public ApiResponse tree(HttpServletRequest req) { return ApiResponse.ok(menus.tree(actor(req))); }
    @GetMapping("/system/menu/roleMenuTreeselect/{id}") public ApiResponse roleTree(HttpServletRequest req,@PathVariable long id) { return ApiResponse.ok(menus.roleTree(actor(req),id)); }
    @GetMapping("/system/menu/{id}") public ApiResponse menu(HttpServletRequest req,@PathVariable long id) { return ApiResponse.ok(menus.detail(actor(req),id)); }
    @PostMapping("/system/menu") public ApiResponse addMenu(HttpServletRequest req,@RequestBody MenuInput input) { menus.add(actor(req),input);return ApiResponse.ok(); }
    @PutMapping("/system/menu") public ApiResponse updateMenu(HttpServletRequest req,@RequestBody MenuInput input) { menus.update(actor(req),input);return ApiResponse.ok(); }
    @DeleteMapping("/system/menu/{id}") public ApiResponse deleteMenu(HttpServletRequest req,@PathVariable long id) { menus.delete(actor(req),id);return ApiResponse.ok(); }
    @GetMapping("/ops/revocations/metrics") public ApiResponse metrics(HttpServletRequest req) {
        security.require(actor(req),"system:revocation:monitor");
        return ApiResponse.ok(Map.of("pending",Long.toString(security.db.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE state='PENDING'",Long.class)),
                "outboxPending",Long.toString(security.db.queryForObject("SELECT COUNT(*) FROM auth_revocation_outbox WHERE state='PENDING'",Long.class)),
                "retryAttempts",Long.toString(security.db.queryForObject("SELECT COALESCE(SUM(attempts),0) FROM auth_revocation_intent",Long.class)),
                "completedThisProcess",Long.toString(revocations.successes()),"failuresThisProcess",Long.toString(revocations.failures())));
    }
}
