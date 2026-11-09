package com.knode.content.article;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicContentController {
    private final PublicContentQuery query;
    public PublicContentController(PublicContentQuery query) { this.query=query; }
    private static Map<String,Object> ok(Object data) { return Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f","data",data); }

    @GetMapping({"/api/blog/article/articleList","/article/articleList"})
    public Map<String,Object> list(@RequestParam(required=false) String pageNum,
            @RequestParam(required=false) String pageSize,@RequestParam(required=false) String categoryId) {
        return ok(query.list(pageNum,pageSize,categoryId));
    }
    @GetMapping({"/api/blog/article/hotArticleList","/article/hotArticleList"})
    public Map<String,Object> hot() { return ok(query.hot()); }
    @GetMapping({"/api/blog/article/{id:-?[0-9]+}","/article/{id:-?[0-9]+}"})
    public Map<String,Object> detail(@PathVariable String id) {
        try { return ok(query.detail(Long.parseLong(id))); }
        catch (NumberFormatException e) { throw new ArticleFailure(400,"Invalid id"); }
    }
    @GetMapping({"/api/blog/category/getCategoryList","/category/getCategoryList"})
    public Map<String,Object> categories() { return ok(query.categories()); }
    @GetMapping({"/api/blog/tag/listAllTag","/tag/listAllTag"})
    public Map<String,Object> tags() { return ok(query.tags()); }

    @ExceptionHandler(ArticleFailure.class)
    public ResponseEntity<Map<String,Object>> articleError(ArticleFailure error) {
        if (error.status()==404) return ResponseEntity.ok(Map.of("code",513,"msg","\u6587\u7ae0\u4e0d\u5b58\u5728"));
        if (error.status()==400) return ResponseEntity.ok(Map.of("code",514,"msg","\u6587\u7ae0\u53c2\u6570\u4e0d\u5408\u6cd5"));
        return ResponseEntity.status(error.status()).body(Map.of("code",error.status()==503?500:error.status(),"msg","Request failed"));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> systemError() {
        return ResponseEntity.internalServerError().body(Map.of("code",500,"msg","\u7cfb\u7edf\u9519\u8bef"));
    }
}
