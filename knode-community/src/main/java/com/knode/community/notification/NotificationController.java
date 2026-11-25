package com.knode.community.notification;

import com.knode.auth.AuthFailure;
import com.knode.auth.UserTokenVerifier;
import com.knode.community.auth.AuthenticationFilter;
import com.knode.community.follow.CommunityBusinessFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/blog/notification", "/notification"})
public class NotificationController {
    private static final Set<String> PAGE_QUERY_KEYS = Set.of("pageNum", "pageSize");
    private static final String MSG_OK = "\u64cd\u4f5c\u6210\u529f";

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestParam(required = false) String pageNum,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest request) {
        UserTokenVerifier.Principal principal = requirePrincipal(request);
        validatePageParams(request);
        return Map.of("code", 200, "msg", MSG_OK, "data", service.inbox(principal.userId(), pageNum, pageSize));
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(HttpServletRequest request) {
        UserTokenVerifier.Principal principal = requirePrincipal(request);
        validateNoQueryParams(request);
        return Map.of("code", 200, "msg", MSG_OK, "data", service.unreadCount(principal.userId()));
    }

    @PutMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable String id, HttpServletRequest request) {
        UserTokenVerifier.Principal principal = requirePrincipal(request);
        validateNoQueryParams(request);
        service.markRead(principal.userId(), id);
        return Map.of("code", 200, "msg", MSG_OK);
    }

    @PutMapping("/read-all")
    public Map<String, Object> markAllRead(HttpServletRequest request) {
        UserTokenVerifier.Principal principal = requirePrincipal(request);
        validateNoQueryParams(request);
        service.markAllRead(principal.userId());
        return Map.of("code", 200, "msg", MSG_OK);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) {
        UserTokenVerifier.Principal principal = requirePrincipal(request);
        validateNoQueryParams(request);
        service.delete(principal.userId(), id);
        return Map.of("code", 200, "msg", MSG_OK);
    }

    private static UserTokenVerifier.Principal requirePrincipal(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthenticationFilter.PRINCIPAL);
        if (attr instanceof UserTokenVerifier.Principal principal && UserTokenVerifier.validUserId(principal.userId())) {
            return principal;
        }
        throw new AuthFailure(401);
    }

    private static void validatePageParams(HttpServletRequest request) {
        Map<String, String[]> params = request.getParameterMap();
        if (!PAGE_QUERY_KEYS.containsAll(params.keySet())
                || params.values().stream().anyMatch(values -> values == null || values.length != 1)) {
            throw CommunityBusinessFailure.paramError();
        }
    }

    private static void validateNoQueryParams(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw CommunityBusinessFailure.paramError();
        }
    }

    @ExceptionHandler(CommunityBusinessFailure.class)
    public ResponseEntity<Map<String, Object>> businessError(CommunityBusinessFailure failure) {
        return ResponseEntity.status(failure.httpStatus())
                .body(Map.of("code", failure.businessCode(), "msg", failure.publicMessage()));
    }

    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String, Object>> authError(AuthFailure failure) {
        int code = failure.status() == 503 ? 500 : failure.status();
        return ResponseEntity.status(failure.status())
                .body(Map.of("code", code, "msg", "Authentication failed"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> systemError() {
        return ResponseEntity.status(503)
                .body(Map.of("code", 500, "msg", "\u793e\u533a\u670d\u52a1\u6682\u4e0d\u53ef\u7528"));
    }
}
