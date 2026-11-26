package com.knode.search.controller;

import com.knode.search.auth.AdminPrincipal;
import com.knode.search.auth.SearchSecurityFilter;
import com.knode.search.model.ApiResponse;
import com.knode.search.ops.SearchAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"/api/admin/content/search", "/content/search"})
public class AdminSearchController {
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final SearchAdminService adminService;

    public AdminSearchController(SearchAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping(value = "/rebuild", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> rebuild(HttpServletRequest request) {
        AdminPrincipal admin = extractAdminPrincipal(request);
        if (admin == null) {
            return unauthorized();
        }
        Map<String, Object> data = adminService.triggerRebuild(admin);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ApiResponse.ok(data));
    }

    @PostMapping(value = "/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> retry(HttpServletRequest request) {
        AdminPrincipal admin = extractAdminPrincipal(request);
        if (admin == null) {
            return unauthorized();
        }
        Map<String, Object> data = adminService.retryFailedWork(admin);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ApiResponse.ok(data));
    }

    @PostMapping(value = "/sync/{articleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncArticle(
            HttpServletRequest request,
            @PathVariable("articleId") String articleId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        AdminPrincipal admin = extractAdminPrincipal(request);
        if (admin == null) {
            return unauthorized();
        }
        Map<String, Object> data = adminService.synchronizeArticle(admin, articleId, sanitizeRequestId(requestId));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ApiResponse.ok(data));
    }

    private static AdminPrincipal extractAdminPrincipal(HttpServletRequest request) {
        Object attr = request.getAttribute(SearchSecurityFilter.ATTR_ADMIN_PRINCIPAL);
        return attr instanceof AdminPrincipal principal ? principal : null;
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Cache-Control", "no-store")
                .body(new ApiResponse<>(401, "未认证的管理员请求", null));
    }

    private static String sanitizeRequestId(String requestId) {
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            return null;
        }
        return requestId;
    }
}
