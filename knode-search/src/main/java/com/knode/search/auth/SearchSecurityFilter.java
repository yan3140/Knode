package com.knode.search.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.AuthFailure;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.auth.Session;
import com.knode.auth.UserTokenVerifier;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class SearchSecurityFilter extends OncePerRequestFilter {
    public static final String ATTR_ADMIN_PRINCIPAL = "knode.search.adminPrincipal";
    public static final String ATTR_SERVICE_PRINCIPAL = "knode.search.servicePrincipal";
    public static final String PERM_WILDCARD = "*:*:*";
    public static final String OPS_READ_PERMISSION = "ops:read";
    public static final String OPS_RETRY_PERMISSION = "ops:retry";

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchSecurityFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final String INTERNAL_OPS_PREFIX = "/internal/v1/ops/consumers";
    private static final String ADMIN_API_PREFIX = "/api/admin/content/search";
    private static final String ADMIN_LEGACY_PREFIX = "/content/search";

    private final UserTokenVerifier userTokenVerifier;
    private final ServiceTokenVerifier serviceTokenVerifier;
    private final AuthSessionStore authSessionStore;
    private final AdminAuthorityClient adminAuthorityClient;
    private final SearchAuthProperties authProperties;
    private final SearchMetrics searchMetrics;
    private final ObjectMapper objectMapper;

    public SearchSecurityFilter(
            UserTokenVerifier userTokenVerifier,
            ServiceTokenVerifier serviceTokenVerifier,
            AuthSessionStore authSessionStore,
            AdminAuthorityClient adminAuthorityClient,
            SearchAuthProperties authProperties,
            SearchMetrics searchMetrics,
            ObjectMapper objectMapper) {
        this.userTokenVerifier = userTokenVerifier;
        this.serviceTokenVerifier = serviceTokenVerifier;
        this.authSessionStore = authSessionStore;
        this.adminAuthorityClient = adminAuthorityClient;
        this.authProperties = authProperties;
        this.searchMetrics = searchMetrics;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String rawUri = request.getRequestURI();
        String path = normalizePath(request);

        if (isInternalOpsPath(path) || (rawUri != null && rawUri.startsWith(INTERNAL_OPS_PREFIX))) {
            handleInternalOps(request, response, filterChain, rawUri, path);
            return;
        }
        if (isAdminSearchPath(path)
                || (rawUri != null && (rawUri.startsWith(ADMIN_API_PREFIX) || rawUri.startsWith(ADMIN_LEGACY_PREFIX)))) {
            handleAdminSearch(request, response, filterChain, rawUri, path);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void handleInternalOps(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            String rawUri,
            String path) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (hasSuspiciousPathVariant(rawUri)) {
            denyInternal(request, response, 400, "INVALID_ARGUMENT", "Malformed request path", false);
            return;
        }
        if (IdentityHeaderGuard.hasForgedIdentityHeader(request)
                || IdentityHeaderGuard.hasAnyHeader(request, "token")) {
            denyInternal(request, response, 401, "INVALID_SERVICE_TOKEN", "Forged identity or viewer token header rejected", false);
            return;
        }

        if (serviceTokenVerifier == null) {
            denyInternal(request, response, 503, "AUTH_STATE_UNAVAILABLE", "Service token verification keys are not configured", true);
            return;
        }
        String bearerToken = IdentityHeaderGuard.extractSingleBearerToken(request);
        if (bearerToken == null) {
            denyInternal(request, response, 401, "INVALID_SERVICE_TOKEN", "Missing or malformed Authorization Bearer token", false);
            return;
        }

        String method = request.getMethod();
        boolean supportedMethod = "GET".equals(method) || "POST".equals(method);
        Set<String> requiredPermission = "GET".equals(method)
                ? Set.of(OPS_READ_PERMISSION)
                : ("POST".equals(method) ? Set.of(OPS_RETRY_PERMISSION) : Set.of());

        String expectedAudience = StringUtils.hasText(authProperties.getExpectedServiceAudience())
                ? authProperties.getExpectedServiceAudience().trim()
                : "knode-search";
        try {
            ServiceTokenVerifier.Principal principal = serviceTokenVerifier.verify(
                    bearerToken, expectedAudience, requiredPermission);
            if (!supportedMethod) {
                denyInternal(request, response, 405, "METHOD_NOT_ALLOWED", "HTTP method not allowed on internal ops route", false);
                return;
            }
            request.setAttribute(ATTR_SERVICE_PRINCIPAL, principal);
            filterChain.doFilter(request, response);
        } catch (AuthFailure failure) {
            int status = failure.status();
            if (status == 403) {
                denyInternal(request, response, 403, "SERVICE_PERMISSION_DENIED", "Service token lacks required permission", false);
            } else if (status == 503) {
                denyInternal(request, response, 503, "AUTH_STATE_UNAVAILABLE", "Service authentication state unavailable", true);
            } else {
                denyInternal(request, response, 401, "INVALID_SERVICE_TOKEN", "Invalid service token", false);
            }
        } catch (Exception ex) {
            LOGGER.warn("event=search_internal_auth_unexpected_error errorType={}", ex.getClass().getSimpleName());
            denyInternal(request, response, 503, "AUTH_STATE_UNAVAILABLE", "Authentication verification failed", true);
        }
    }

    private void handleAdminSearch(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            String rawUri,
            String path) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (hasSuspiciousPathVariant(rawUri)) {
            denyAdmin(response, 401, 401, "未认证或请求无效");
            return;
        }
        if (IdentityHeaderGuard.hasForgedIdentityHeader(request)) {
            denyAdmin(response, 401, 401, "拒绝伪造身份头");
            return;
        }
        // Public admin routes must never accept machine Authorization Bearer tokens or mixed credentials.
        if (IdentityHeaderGuard.hasAnyHeader(request, "Authorization")) {
            denyAdmin(response, 401, 401, "后台管理入口仅接受管理员 token 头");
            return;
        }
        String rawToken = IdentityHeaderGuard.extractSingleNonBlankHeader(request, "token");
        if (rawToken == null) {
            denyAdmin(response, 401, 401, "缺少或重复的管理员登录凭证");
            return;
        }
        if (userTokenVerifier == null || authSessionStore == null || adminAuthorityClient == null) {
            denyAdmin(response, 503, 500, "认证服务暂不可用");
            return;
        }

        try {
            UserTokenVerifier.VerifiedToken verified = userTokenVerifier.verifyToken(rawToken);
            Optional<Session> session = authSessionStore.loadSession(verified.sid());
            UserTokenVerifier.Principal principal = userTokenVerifier.validateSession(verified, session, "admin");

            String requestId = sanitizeRequestId(request.getHeader("X-Request-Id"));
            AdminAuthorityClient.AdminAuthority authority = adminAuthorityClient.fetchCurrentAuthority(rawToken, requestId);
            if (!principal.userId().equals(authority.userId())) {
                LOGGER.warn("event=search_admin_subject_mismatch principalUserId={}", principal.userId());
                denyAdmin(response, 503, 500, "认证主体校验异常");
                return;
            }

            List<String> requiredPermissions = resolveAdminActionPermissions(path);
            if (!isAuthorizedAdminAction(authority.permissions(), requiredPermissions)) {
                denyAdmin(response, 403, 403, "缺少该搜索管理操作的有效授权");
                return;
            }

            request.setAttribute(ATTR_ADMIN_PRINCIPAL,
                    new AdminPrincipal(principal.userId(), principal.sid(), authority.permissions()));
            filterChain.doFilter(request, response);
        } catch (AuthFailure failure) {
            int status = failure.status();
            if (status == 403) {
                denyAdmin(response, 403, 403, "无权限访问后台搜索管理接口");
            } else if (status == 503) {
                denyAdmin(response, 503, 500, "认证服务暂不可用");
            } else {
                denyAdmin(response, 401, 401, "管理员登录状态无效或已过期");
            }
        } catch (Exception ex) {
            LOGGER.warn("event=search_admin_auth_unexpected_error errorType={}", ex.getClass().getSimpleName());
            denyAdmin(response, 503, 500, "认证服务暂不可用");
        }
    }

    private List<String> resolveAdminActionPermissions(String path) {
        SearchAuthProperties.AdminProps admin = authProperties.getAdmin();
        String suffix = stripAdminPrefix(path);
        if ("/rebuild".equals(suffix)) {
            return sanitizePermissions(admin.getRebuildPermissions());
        }
        if ("/retry".equals(suffix)) {
            return sanitizePermissions(admin.getRetryPermissions());
        }
        if (suffix.startsWith("/sync/")) {
            return sanitizePermissions(admin.getSyncPermissions());
        }
        return List.of();
    }

    private static boolean isAuthorizedAdminAction(Set<String> grantedPermissions, List<String> requiredActionPermissions) {
        if (grantedPermissions == null || grantedPermissions.isEmpty()) {
            return false;
        }
        // Only explicit DB-backed *:*:* or a configured published action permission grants access;
        // unpublished actions remain fail-closed for all non-wildcard admins.
        if (grantedPermissions.contains(PERM_WILDCARD)) {
            return true;
        }
        if (requiredActionPermissions == null || requiredActionPermissions.isEmpty()) {
            return false;
        }
        for (String required : requiredActionPermissions) {
            if (grantedPermissions.contains(required)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sanitizePermissions(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private void denyAdmin(HttpServletResponse response, int httpStatus, int businessCode, String message)
            throws IOException {
        searchMetrics.recordAuthDenied("admin", httpStatus);
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), new ApiResponse<Void>(businessCode, message, null));
    }

    private void denyInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            int httpStatus,
            String errorCode,
            String message,
            boolean retryable) throws IOException {
        searchMetrics.recordAuthDenied("internal_ops", httpStatus);
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        Map<String, Object> errorObj = new LinkedHashMap<>();
        errorObj.put("code", errorCode);
        errorObj.put("message", message);
        errorObj.put("retryable", retryable);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", errorObj);
        String requestId = sanitizeRequestId(request.getHeader("X-Request-Id"));
        if (requestId != null) {
            payload.put("requestId", requestId);
        }
        objectMapper.writeValue(response.getWriter(), payload);
    }

    private static String sanitizeRequestId(String requestId) {
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            return null;
        }
        return requestId;
    }

    private static String normalizePath(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!StringUtils.hasText(path)) {
            path = request.getRequestURI();
        }
        return path == null ? "" : path;
    }

    private static boolean hasSuspiciousPathVariant(String rawUri) {
        if (rawUri == null) {
            return false;
        }
        return rawUri.contains("..") || rawUri.contains(";") || rawUri.contains("//") || rawUri.contains("\\");
    }

    private static boolean isInternalOpsPath(String path) {
        return path.equals(INTERNAL_OPS_PREFIX) || path.startsWith(INTERNAL_OPS_PREFIX + "/");
    }

    private static boolean isAdminSearchPath(String path) {
        return path.equals(ADMIN_API_PREFIX)
                || path.startsWith(ADMIN_API_PREFIX + "/")
                || path.equals(ADMIN_LEGACY_PREFIX)
                || path.startsWith(ADMIN_LEGACY_PREFIX + "/");
    }

    private static String stripAdminPrefix(String path) {
        if (path.startsWith(ADMIN_API_PREFIX)) {
            return path.substring(ADMIN_API_PREFIX.length());
        }
        if (path.startsWith(ADMIN_LEGACY_PREFIX)) {
            return path.substring(ADMIN_LEGACY_PREFIX.length());
        }
        return path;
    }
}
