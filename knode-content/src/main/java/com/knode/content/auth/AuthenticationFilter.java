package com.knode.content.auth;

import com.knode.auth.AuthFailure;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.auth.UserTokenVerifier;
import com.knode.content.relay.MachineAuth;
import com.knode.content.snapshot.SnapshotFailure;
import com.knode.content.snapshot.SnapshotRoutes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class AuthenticationFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL = AuthenticationFilter.class.getName() + ".principal";
    private final UserTokenVerifier verifier;
    private final SessionReader sessions;
    private final MachineAuth machines;

    public AuthenticationFilter(UserTokenVerifier verifier,SessionReader sessions,MachineAuth machines) {
        this.verifier=verifier; this.sessions=sessions; this.machines=machines;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().equals("/actuator/health") || request.getRequestURI().startsWith("/actuator/health/")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            if (Collections.list(request.getHeaderNames()).stream().anyMatch(AuthenticationFilter::forbiddenHeader))
                throw new AuthFailure(401);
            String snapshotPermission = SnapshotRoutes.permission(request);
            String permission = snapshotPermission == null ? machinePermission(request) : snapshotPermission;
            if(permission!=null) {
                if (snapshotPermission != null && SnapshotRoutes.viewerHeaderPresent(request)) throw new AuthFailure(401);
                request.setAttribute(MachineAuth.PRINCIPAL,machines.authenticate(request,permission));
                chain.doFilter(request,response);
                return;
            }
            if (isInternalPath(request)) throw new AuthFailure(401);
            if (isOptionalBlogProfileRead(request)) {
                List<String> optionalHeaders = Collections.list(request.getHeaders("token"));
                if (!optionalHeaders.isEmpty()) {
                    if (optionalHeaders.size() != 1 || optionalHeaders.get(0).isBlank() || optionalHeaders.get(0).contains(","))
                        throw new AuthFailure(401);
                    UserTokenVerifier.VerifiedToken token = verifier.verifyToken(optionalHeaders.get(0));
                    Optional<Session> state = readSession(token.sid());
                    UserTokenVerifier.Principal principal = verifier.validateSession(token, state, "blog");
                    request.setAttribute(PRINCIPAL, principal);
                }
                chain.doFilter(request, response);
                return;
            }
            if (isPublicRead(request)) {
                chain.doFilter(request, response);
                return;
            }
            List<String> headers = Collections.list(request.getHeaders("token"));
            if (headers.size() != 1 || headers.get(0).isBlank() || headers.get(0).contains(","))
                throw new AuthFailure(401);
            UserTokenVerifier.VerifiedToken token = verifier.verifyToken(headers.get(0));
            Optional<Session> state = readSession(token.sid());
            String path = request.getRequestURI();
            String scope = path.startsWith("/api/admin/") || path.startsWith("/content/") || path.equals("/upload")
                    || path.startsWith("/ops/") ? "admin" : "blog";
            UserTokenVerifier.Principal principal = verifier.validateSession(token, state, scope);
            request.setAttribute(PRINCIPAL, principal);
        } catch (AuthFailure failure) {
            if (SnapshotRoutes.inNamespace(request)) {
                SnapshotFailure.writeAuth(request, response, failure.status());
                return;
            }
            response.setStatus(failure.status());
            response.setContentType("application/json");
            int code = failure.status() == 503 ? 500 : failure.status();
            response.getWriter().write("{\"code\":" + code + ",\"msg\":\"Authentication failed\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String machinePermission(HttpServletRequest request) {
        String path=request.getRequestURI();
        if("GET".equals(request.getMethod()) && path.equals("/internal/v1/ops/outbox/summary")) return "ops:read";
        if("POST".equals(request.getMethod()) && path.matches("/internal/v1/ops/outbox/[A-Za-z0-9._-]{1,64}/redeliver")) return "ops:redeliver";
        return null;
    }

    private static boolean isInternalPath(HttpServletRequest request) {
        return isInternalPath(request.getRequestURI()) || isInternalPath(request.getServletPath()) || isInternalPath(request.getPathInfo());
    }
    private static boolean isInternalPath(String path) {
        return path!=null && (path.equals("/internal") || path.startsWith("/internal/") || path.startsWith("/internal;"));
    }

    private static boolean isOptionalBlogProfileRead(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        String p = request.getRequestURI();
        return p.matches("/(?:api/blog/)?user/profile/[^/]{1,64}");
    }

    private static boolean isPublicRead(HttpServletRequest request) {
        String p = request.getRequestURI();
        if ("PUT".equals(request.getMethod()))
            return p.matches("/(?:api/blog/)?article/updateViewCount/[^/]{1,64}");
        if (!"GET".equals(request.getMethod())) return false;
        return p.equals("/api/blog/article/articleList") || p.equals("/article/articleList")
                || p.equals("/api/blog/article/hotArticleList") || p.equals("/article/hotArticleList")
                || p.equals("/api/blog/category/getCategoryList") || p.equals("/category/getCategoryList")
                || p.equals("/api/blog/tag/listAllTag") || p.equals("/tag/listAllTag")
                || p.equals("/api/blog/link/getAllLink") || p.equals("/link/getAllLink")
                || p.equals("/api/blog/comment/commentList") || p.equals("/comment/commentList")
                || p.equals("/api/blog/comment/linkCommentList") || p.equals("/comment/linkCommentList")
                || p.matches("/(?:api/blog/)?article/-?[0-9]+");
    }

    private Optional<Session> readSession(String sid) {
        try {
            return sessions.read(sid).map(raw -> {
                Session state = SessionCodec.decode(raw);
                if (state.schemaVersion() != 1 || !UserTokenVerifier.validUuid(state.sid())
                        || !UserTokenVerifier.validUserId(state.userId()) || !UserTokenVerifier.validScope(state.scope())
                        || !(state.status().equals("ACTIVE") || state.status().equals("REVOKED"))
                        || state.expiresAt() <= 0) throw new AuthFailure(503);
                return state;
            });
        } catch (AuthFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthFailure(503);
        }
    }

    private static boolean forbiddenHeader(String raw) {
        String h = raw.toLowerCase(Locale.ROOT);
        return h.startsWith("x-internal-") || h.startsWith("x-user-") || h.startsWith("x-principal-")
                || h.startsWith("x-service-") || h.startsWith("x-identity-") || h.startsWith("x-authenticated-")
                || h.equals("x-forwarded-user") || h.equals("x-remote-user") || h.equals("x-auth-request-user");
    }
}
