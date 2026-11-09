package com.knode.content.article;

import com.knode.auth.UserTokenVerifier;
import com.knode.content.auth.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
        if (failure.status() == 400)
            return ResponseEntity.ok(Map.of("code", 514, "msg", "\u6587\u7ae0\u53c2\u6570\u4e0d\u5408\u6cd5"));
        if (failure.status() == 404)
            return ResponseEntity.ok(Map.of("code", 513, "msg", "\u6587\u7ae0\u4e0d\u5b58\u5728"));
        int code = failure.status() == 503 ? 500 : failure.status();
        return ResponseEntity.status(failure.status()).body(Map.of("code", code, "msg", failure.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformedRequest() {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "Invalid request"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> systemFailure() {
        return ResponseEntity.internalServerError().body(Map.of("code", 500, "msg", "\u7cfb\u7edf\u9519\u8bef"));
    }

}
