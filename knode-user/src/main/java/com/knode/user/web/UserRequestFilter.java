package com.knode.user.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.user.account.AccountService;
import com.knode.user.auth.AuthenticationFailure;
import com.knode.user.auth.RevocationCoordinator;
import com.knode.user.auth.UserTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class UserRequestFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL=UserTokens.Principal.class.getName();
    private final UserTokens tokens;
    private final AccountService accounts;
    private final RevocationCoordinator revocations;
    private final ObjectMapper json;
    private final org.springframework.core.env.Environment environment;
    public UserRequestFilter(UserTokens tokens,AccountService accounts,RevocationCoordinator revocations,ObjectMapper json,
                             org.springframework.core.env.Environment environment) {
        this.tokens=tokens;this.accounts=accounts;this.revocations=revocations;this.json=json;this.environment=environment;
    }
    private boolean managementRequest(HttpServletRequest req) {
        try {
            int port=environment.getProperty("management.server.port",Integer.class,19092);
            return port>0 && req.getLocalPort()==port
                    && java.net.InetAddress.getByName(req.getLocalAddr()).isLoopbackAddress()
                    && java.net.InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception e) {return false;}
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        try {
            for (String name:Collections.list(req.getHeaderNames())) {
                String n=name.toLowerCase(Locale.ROOT);
                if (n.startsWith("x-internal-") || n.startsWith("x-user-") || n.startsWith("x-principal-")
                        || n.startsWith("x-service-") || n.startsWith("x-identity-") || n.startsWith("x-authenticated-")
                        || n.equals("x-forwarded-user") || n.equals("x-remote-user") || n.equals("x-auth-request-user"))
                    throw new AuthenticationFailure(401);
            }
            var values=Collections.list(req.getHeaders("token"));
            if (values.size()>1 || (values.size()==1 && values.get(0).contains(","))) throw new AuthenticationFailure(401);
            String rawPath=req.getRequestURI().substring(req.getContextPath().length());
            String lower=rawPath.toLowerCase(Locale.ROOT);
            if (rawPath.indexOf(';')>=0 || rawPath.indexOf('\\')>=0 || rawPath.contains("//")
                    || lower.contains("%3b") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%25"))
                throw new AuthenticationFailure(401);
            String path=req.getServletPath();
            if (path.equals("/actuator") || path.startsWith("/actuator/")) {
                boolean allowed="GET".equals(req.getMethod()) && java.util.Set.of("/actuator/health","/actuator/health/liveness",
                        "/actuator/health/readiness","/actuator/prometheus").contains(path) && managementRequest(req);
                if (!allowed) throw new AuthenticationFailure(401);
                chain.doFilter(req,res);return;
            }
            if (path.startsWith("/internal/")) {
                var auth=Collections.list(req.getHeaders("Authorization"));
                if (!values.isEmpty() || auth.size()!=1 || auth.get(0).contains(",")) throw new AuthenticationFailure(401);
                chain.doFilter(req,res);
                return;
            }
            boolean pub="POST".equals(req.getMethod()) && (path.equals("/api/blog/auth/login")
                    || path.equals("/api/admin/auth/login") || path.equals("/api/blog/user/register"));
            boolean health="GET".equals(req.getMethod()) && (path.equals("/health/live") || path.equals("/health/ready"));
            if (!pub && !health) {
                if (values.size()!=1) throw new AuthenticationFailure(401);
                String scope=path.startsWith("/api/admin/")?"admin":"blog";
                var principal=tokens.authenticateAnyScope(values.get(0));
                long id;
                try { id=Long.parseLong(principal.userId()); }
                catch (NumberFormatException e) { throw new AuthenticationFailure(401); }
                boolean pending=revocations.pending(principal.sid());
                boolean logout="POST".equals(req.getMethod()) && (path.equals("/api/blog/auth/logout") || path.equals("/api/admin/auth/logout"));
                if (pending && !logout) throw new AuthenticationFailure(503);
                if (!pending) {
                    accounts.active(id,principal.scope());
                    if (!accounts.sessionActive(principal.sid(),id,principal.scope())) throw new AuthenticationFailure(401);
                }
                if (!scope.equals(principal.scope())) throw new AuthenticationFailure(403);
                req.setAttribute(PRINCIPAL,principal);
            }
        } catch (AuthenticationFailure e) {
            if (req.getServletPath().startsWith("/internal/")) sendInternal(res,e.status()); else send(res,e.status());
            return;
        } catch (DataAccessException | TransactionException e) {
            if (req.getServletPath().startsWith("/internal/")) sendInternal(res,503); else send(res,503);
            return;
        }
        chain.doFilter(req,res);
    }
    private void sendInternal(HttpServletResponse res,int status) throws IOException {
        res.setStatus(status);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(),java.util.Map.of("error",java.util.Map.of("code",status==503?"DEPENDENCY_UNAVAILABLE":"INVALID_SERVICE_TOKEN","message","Request rejected","retryable",status==503)));
    }
    private void send(HttpServletResponse res,int status) throws IOException {
        res.setStatus(status);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(),new ApiResponse(status==503?500:status,
                status==503?"认证服务暂不可用":status==403?"无权限操作":"需要登录后操作",null));
    }
}
