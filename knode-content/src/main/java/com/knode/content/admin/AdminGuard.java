package com.knode.content.admin;

import com.knode.auth.AuthFailure;
import com.knode.auth.UserTokenVerifier;
import com.knode.content.auth.AuthenticationFilter;
import com.knode.content.profile.UserProfileClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AdminGuard {
    private final UserProfileClient users;
    public AdminGuard(UserProfileClient users) { this.users=users; }
    public UserTokenVerifier.Principal require(HttpServletRequest request,String... allowed) {
        UserTokenVerifier.Principal p=(UserTokenVerifier.Principal)request.getAttribute(AuthenticationFilter.PRINCIPAL);
        if (p==null || !"admin".equals(p.scope())) throw new AuthFailure(403);
        Set<String> permissions=users.adminPermissions(request.getHeader("token"),p.userId());
        if (!permissions.contains("*:*:*") && Arrays.stream(allowed).noneMatch(permissions::contains)) throw new AuthFailure(403);
        return p;
    }
}
