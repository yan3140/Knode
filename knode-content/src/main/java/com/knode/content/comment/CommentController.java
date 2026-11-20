package com.knode.content.comment;

import com.knode.auth.UserTokenVerifier;
import com.knode.content.auth.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommentController {
    private final CommentCommandService commands;
    private final CommentQuery query;
    private final CommentRateLimit limit;
    public CommentController(CommentCommandService commands,CommentQuery query,CommentRateLimit limit) {
        this.commands=commands; this.query=query; this.limit=limit;
    }
    @PostMapping({"/api/blog/comment","/comment"})
    public ResponseEntity<Map<String,Object>> create(@RequestBody CommentInput input,HttpServletRequest request) {
        CommentRules.Valid valid=CommentRules.validate(input);
        var principal=(UserTokenVerifier.Principal)request.getAttribute(AuthenticationFilter.PRINCIPAL);
        if(principal==null) throw new CommentFailure(401,401);
        int retry=limit.consume(principal.userId(),request.getRemoteAddr(),valid.content());
        if(retry>0) return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER,Integer.toString(retry))
                .body(Map.of("code",429,"msg","Too many requests"));
        commands.create(principal.userId(),valid,(String)request.getAttribute("knode.requestId"));
        return ResponseEntity.ok(Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f"));
    }
    @GetMapping({"/api/blog/comment/commentList","/comment/commentList"})
    public Map<String,Object> article(@RequestParam(required=false) String articleId,@RequestParam(required=false) String pageNum,
                                      @RequestParam(required=false) String pageSize) {
        return Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f","data",query.list("0",articleId,pageNum,pageSize));
    }
    @GetMapping({"/api/blog/comment/linkCommentList","/comment/linkCommentList"})
    public Map<String,Object> links(@RequestParam(required=false) String articleId,@RequestParam(required=false) String pageNum,
                                    @RequestParam(required=false) String pageSize) {
        return Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f","data",query.list("1",articleId,pageNum,pageSize));
    }
    @ExceptionHandler(CommentFailure.class)
    public ResponseEntity<Map<String,Object>> failure(CommentFailure failure) {
        String message=switch(failure.code()) {
            case 506 -> "\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a";
            case 513 -> "\u6587\u7ae0\u4e0d\u5b58\u5728";
            case 515 -> failure.status()==413?"Comment thread exceeds read budget":"\u53c2\u6570\u4e0d\u5408\u6cd5";
            case 403 -> "Comment forbidden";
            default -> "Service unavailable";
        };
        return ResponseEntity.status(failure.status()).body(Map.of("code",failure.code(),"msg",message));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed() { return ResponseEntity.badRequest().body(Map.of("code",400,"msg","Invalid request")); }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> system() { return ResponseEntity.internalServerError().body(Map.of("code",500,"msg","Service unavailable")); }
}
