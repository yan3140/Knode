package com.knode.community.auth;

import com.knode.auth.AuthFailure;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.auth.UserTokenVerifier;
import com.knode.community.support.InternalFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class AuthenticationFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL = AuthenticationFilter.class.getName() + ".principal";
    public static final String INTERNAL_RELATIONS_BATCH = "/internal/v1/community/relations:batch";
    public static final String INTERNAL_NOTIFICATIONS_SUMMARY = "/internal/v1/community/notifications/summary";
    public static final String INTERNAL_OPS_SUMMARY = "/internal/v1/ops/consumers/summary";
    public static final String INTERNAL_OPS_EVENTS = "/internal/v1/ops/consumers/events";
    public static final String INTERNAL_OPS_RETRY = "/internal/v1/ops/consumers/retry";
    public static final String PERM_RELATIONS_READ = "community:relations:read";
    public static final String PERM_NOTIFICATIONS_READ = "community:notifications:read";
    public static final String PERM_OPS_READ = "ops:read";
    public static final String PERM_OPS_RETRY = "ops:retry";

    private static final Pattern INTERNAL_OPS_EVENT_RETRY =
            Pattern.compile("^/internal/v1/ops/consumers/([^/]{1,128})/retry$");
    private static final Pattern OPTIONAL_PUBLIC_FOLLOWING =
            Pattern.compile("^/(?:api/blog/)?follow/([^/;]{1,64})/following$");

    private final UserTokenVerifier verifier;
    private final SessionReader sessions;
    private final MachineAuth machines;

    public AuthenticationFilter(UserTokenVerifier verifier, SessionReader sessions, MachineAuth machines) {
        this.verifier = verifier;
        this.sessions = sessions;
        this.machines = machines;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if ("/actuator/health".equals(uri) || uri.startsWith("/actuator/health/") || "/actuator/prometheus".equals(uri)) {
            chain.doFilter(request, response);
            return;
        }
        boolean internalNamespace = inInternalNamespace(request);
        try {
            if (Collections.list(request.getHeaderNames()).stream().anyMatch(AuthenticationFilter::forbiddenHeader)) {
                if (internalNamespace) {
                    throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Forbidden identity header", false);
                }
                throw new AuthFailure(401);
            }
            if (internalNamespace) {
                authenticateInternal(request);
                chain.doFilter(request, response);
                return;
            }
            if (isOptionalPublicFollowRead(request)) {
                List<String> optionalHeaders = Collections.list(request.getHeaders("token"));
                if (!optionalHeaders.isEmpty()) {
                    request.setAttribute(PRINCIPAL, verifyBlogUserHeaders(optionalHeaders));
                }
                chain.doFilter(request, response);
                return;
            }
            List<String> headers = Collections.list(request.getHeaders("token"));
            request.setAttribute(PRINCIPAL, verifyBlogUserHeaders(headers));
        } catch (InternalFailure failure) {
            failure.write(request, response);
            return;
        } catch (AuthFailure failure) {
            response.setStatus(failure.status());
            response.setContentType("application/json;charset=UTF-8");
            int code = failure.status() == 503 ? 500 : failure.status();
            response.getWriter().write("{\"code\":" + code + ",\"msg\":\"Authentication failed\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private void authenticateInternal(HttpServletRequest request) {
        String rawUri = request.getRequestURI();
        if (rawUri == null || rawUri.contains("//") || rawUri.contains(";") || rawUri.contains("%")) {
            throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Invalid internal path", false);
        }
        if ("POST".equals(request.getMethod()) && INTERNAL_RELATIONS_BATCH.equals(rawUri)) {
            request.setAttribute(MachineAuth.PRINCIPAL, machines.authenticate(request, PERM_RELATIONS_READ, true));
            List<String> viewerHeaders = Collections.list(request.getHeaders("token"));
            if (!viewerHeaders.isEmpty()) {
                request.setAttribute(PRINCIPAL, verifyInternalViewerHeaders(viewerHeaders));
            }
            return;
        }
        if ("GET".equals(request.getMethod()) && INTERNAL_NOTIFICATIONS_SUMMARY.equals(rawUri)) {
            request.setAttribute(MachineAuth.PRINCIPAL, machines.authenticate(request, PERM_NOTIFICATIONS_READ, true));
            List<String> receiverHeaders = Collections.list(request.getHeaders("token"));
            if (receiverHeaders.isEmpty()) {
                throw new InternalFailure(401, "SESSION_REVOKED", "Receiver user token is required", false);
            }
            request.setAttribute(PRINCIPAL, verifyInternalViewerHeaders(receiverHeaders));
            return;
        }
        if ("GET".equals(request.getMethod()) && (INTERNAL_OPS_SUMMARY.equals(rawUri) || INTERNAL_OPS_EVENTS.equals(rawUri))) {
            request.setAttribute(MachineAuth.PRINCIPAL, machines.authenticate(request, PERM_OPS_READ, false));
            return;
        }
        if ("POST".equals(request.getMethod()) && (INTERNAL_OPS_RETRY.equals(rawUri) || INTERNAL_OPS_EVENT_RETRY.matcher(rawUri).matches())) {
            request.setAttribute(MachineAuth.PRINCIPAL, machines.authenticate(request, PERM_OPS_RETRY, false));
            return;
        }
        throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Unknown or unauthorized internal endpoint", false);
    }

    private UserTokenVerifier.Principal verifyInternalViewerHeaders(List<String> headers) {
        if (headers.size() != 1 || headers.get(0).isBlank() || headers.get(0).contains(",")) {
            throw new InternalFailure(401, "SESSION_REVOKED", "Invalid viewer token header", false);
        }
        try {
            UserTokenVerifier.VerifiedToken token = verifier.verifyToken(headers.get(0));
            Optional<Session> state = readSession(token.sid());
            return verifier.validateSession(token, state, "blog");
        } catch (AuthFailure failure) {
            if (failure.status() == 503) {
                throw new InternalFailure(503, "AUTH_STATE_UNAVAILABLE", "Unable to verify viewer session", true);
            }
            if (failure.status() == 403) {
                throw new InternalFailure(403, "USER_ACCESS_DENIED", "Viewer token lacks blog scope", false);
            }
            throw new InternalFailure(401, "SESSION_REVOKED", "Viewer token or session is invalid", false);
        }
    }

    private UserTokenVerifier.Principal verifyBlogUserHeaders(List<String> headers) {
        if (headers.size() != 1 || headers.get(0).isBlank() || headers.get(0).contains(",")) {
            throw new AuthFailure(401);
        }
        UserTokenVerifier.VerifiedToken token = verifier.verifyToken(headers.get(0));
        Optional<Session> state = readSession(token.sid());
        return verifier.validateSession(token, state, "blog");
    }

    private static boolean isOptionalPublicFollowRead(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.contains("//") || uri.contains(";")) {
            return false;
        }
        Matcher matcher = OPTIONAL_PUBLIC_FOLLOWING.matcher(uri);
        if (!matcher.matches()) {
            return false;
        }
        String segment = matcher.group(1);
        return !"status".equals(segment);
    }

    private static boolean inInternalNamespace(HttpServletRequest request) {
        return inInternalNamespace(request.getRequestURI())
                || inInternalNamespace(request.getServletPath())
                || inInternalNamespace(request.getPathInfo());
    }

    private static boolean inInternalNamespace(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.equals("/internal")
                || lower.startsWith("/internal/")
                || lower.startsWith("/internal;")
                || lower.contains("//internal");
    }

    private Optional<Session> readSession(String sid) {
        try {
            return sessions.read(sid).map(raw -> {
                Session state = SessionCodec.decode(raw);
                if (state.schemaVersion() != 1
                        || !UserTokenVerifier.validUuid(state.sid())
                        || !UserTokenVerifier.validUserId(state.userId())
                        || !UserTokenVerifier.validScope(state.scope())
                        || !("ACTIVE".equals(state.status()) || "REVOKED".equals(state.status()))
                        || state.expiresAt() <= 0) {
                    throw new AuthFailure(503);
                }
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
        return h.startsWith("x-internal-")
                || h.startsWith("x-user-")
                || h.startsWith("x-principal-")
                || h.startsWith("x-service-")
                || h.startsWith("x-identity-")
                || h.startsWith("x-authenticated-")
                || h.equals("x-forwarded-user")
                || h.equals("x-remote-user")
                || h.equals("x-auth-request-user")
                || h.equals("x-actor")
                || h.startsWith("x-actor-")
                || h.equals("x-operator")
                || h.startsWith("x-operator-")
                || h.equals("x-viewer")
                || h.startsWith("x-viewer-")
                || h.equals("viewerid");
    }
}
