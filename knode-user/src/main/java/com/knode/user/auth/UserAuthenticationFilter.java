package com.knode.user.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Register explicitly only on protected routes; resource authorization remains separate. */
public final class UserAuthenticationFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = UserTokens.Principal.class.getName();
    private final UserTokens tokens;
    private final String scope;
    public UserAuthenticationFilter(UserTokens tokens, String scope) {
        this.tokens = java.util.Objects.requireNonNull(tokens);
        if (!"blog".equals(scope) && !"admin".equals(scope)) throw new IllegalArgumentException("scope");
        this.scope = scope;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        UserTokens.Principal principal;
        try {
            principal = tokens.authenticate(request.getHeader("token"), scope);
        } catch (AuthenticationFailure failure) {
            response.setStatus(failure.status());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(failure.status() == 503
                    ? "{\"code\":500,\"msg\":\"Authentication state unavailable\"}"
                    : "{\"code\":401,\"msg\":\"Authentication required\"}");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }
}
