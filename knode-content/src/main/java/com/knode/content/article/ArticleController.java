package com.knode.content.article;

import com.knode.auth.UserTokenVerifier;
import com.knode.content.auth.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ArticleController {
    private final ArticleCommandService service;
    public ArticleController(ArticleCommandService service) { this.service = service; }

    @PostMapping({"/api/blog/article", "/article"})
    public Map<String,Object> create(@RequestBody ArticleCommand input, HttpServletRequest request) {
        UserTokenVerifier.Principal principal = (UserTokenVerifier.Principal) request.getAttribute(AuthenticationFilter.PRINCIPAL);
        service.create(principal.userId(), input, (String) request.getAttribute("knode.requestId"));
        return Map.of("code", 200, "msg", "\u64cd\u4f5c\u6210\u529f");
    }

    @ExceptionHandler(ArticleFailure.class)
    public ResponseEntity<Map<String,Object>> error(ArticleFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.status(), "msg", failure.getMessage()));
    }
}
