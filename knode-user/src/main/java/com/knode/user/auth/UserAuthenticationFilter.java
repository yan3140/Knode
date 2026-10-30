package com.knode.user.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/** Register explicitly on protected routes. Every request must authenticate independently. */
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
            for (String name : Collections.list(request.getHeaderNames())) {
                String n = name.toLowerCase(Locale.ROOT);
                if (n.startsWith("x-internal-") || n.startsWith("x-user-")
                        || n.startsWith("x-principal-") || n.startsWith("x-service-") || n.startsWith("x-identity-")
                        || n.startsWith("x-authenticated-") || n.equals("x-forwarded-user")
                        || n.equals("x-remote-user") || n.equals("x-auth-request-user"))
                    throw new AuthenticationFailure(401);
            }
            var values = Collections.list(request.getHeaders("token"));
            if (values.size() != 1 || values.get(0).contains(",")) throw new AuthenticationFailure(401);
            principal = tokens.authenticate(values.get(0), scope);
        } catch (AuthenticationFailure failure) {
            response.setStatus(failure.status());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(switch (failure.status()) {
                case 503 -> "{\"code\":500,\"msg\":\"Authentication state unavailable\"}";
                case 403 -> "{\"code\":403,\"msg\":\"Insufficient scope\"}";
                default -> "{\"code\":401,\"msg\":\"Authentication required\"}";
            });
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }
}
