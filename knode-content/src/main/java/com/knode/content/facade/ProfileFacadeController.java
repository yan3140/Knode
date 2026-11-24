package com.knode.content.facade;

import com.knode.auth.UserTokenVerifier;
import com.knode.content.auth.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProfileFacadeController {
    private final ProfileFacadeService service;

    public ProfileFacadeController(ProfileFacadeService service) {
        this.service = service;
    }

    @GetMapping({"/api/blog/user/profile/{id}", "/user/profile/{id}"})
    public Map<String, Object> profile(
            @PathVariable String id,
            @RequestParam(required = false) String pageNum,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest request) {
        Object attr = request.getAttribute(AuthenticationFilter.PRINCIPAL);
        String viewerId = attr instanceof UserTokenVerifier.Principal principal ? principal.userId() : null;
        String viewerToken = viewerId != null ? request.getHeader("token") : null;
        Map<String, Object> data = service.profile(id, pageNum, pageSize, viewerId, viewerToken);
        return Map.of("code", 200, "msg", "\u64cd\u4f5c\u6210\u529f", "data", data);
    }

    @ExceptionHandler(FacadeFailure.class)
    public ResponseEntity<Map<String, Object>> failure(FacadeFailure failure) {
        return ResponseEntity.status(failure.status())
                .body(Map.of("code", failure.code(), "msg", failure.publicMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> unexpected() {
        return ResponseEntity.status(503)
                .body(Map.of("code", 500, "msg", "\u670d\u52a1\u6682\u4e0d\u53ef\u7528"));
    }
}
