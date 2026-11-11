package com.knode.content.admin;

import com.knode.auth.AuthFailure;
import com.knode.auth.UserTokenVerifier;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.article.ArticleFailure;
import com.knode.content.auth.AuthenticationFilter;
import com.knode.content.profile.UserProfileClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminContentController {
    private final UserProfileClient users;
    private final AdminContentQuery queries;
    private final ArticleCommandService commands;
    public AdminContentController(UserProfileClient users,AdminContentQuery queries,ArticleCommandService commands) {
        this.users=users; this.queries=queries; this.commands=commands;
    }
    private UserTokenVerifier.Principal require(HttpServletRequest request,String... allowed) {
        UserTokenVerifier.Principal principal=(UserTokenVerifier.Principal)request.getAttribute(AuthenticationFilter.PRINCIPAL);
        if (principal==null || !"admin".equals(principal.scope())) throw new AuthFailure(403);
        Set<String> permissions=users.adminPermissions(request.getHeader("token"),principal.userId());
        if (!permissions.contains("*:*:*") && Arrays.stream(allowed).noneMatch(permissions::contains)) throw new AuthFailure(403);
        return principal;
    }
    private static Map<String,Object> ok() { return Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f"); }
    private static Map<String,Object> ok(Object data) { return Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f","data",data); }
    private static String requestId(HttpServletRequest r) { return (String)r.getAttribute("knode.requestId"); }

    @PostMapping({"/api/admin/content/article","/content/article"})
    public Map<String,Object> create(@RequestBody AdminArticleInput input,HttpServletRequest request) {
        var p=require(request,"content:article:add");
        commands.adminCreate(p.userId(),input.command(),input.isTop(),requestId(request)); return ok();
    }
    @GetMapping({"/api/admin/content/article/list","/content/article/list"})
    public Map<String,Object> list(@RequestParam(required=false) String pageNum,@RequestParam(required=false) String pageSize,
            @RequestParam(required=false) String title,@RequestParam(required=false) String summary,
            @RequestParam(required=false) String categoryId,@RequestParam(required=false) String status,HttpServletRequest request) {
        require(request,"content:article:list"); return ok(queries.articles(pageNum,pageSize,title,summary,categoryId,status));
    }
    @GetMapping({"/api/admin/content/article/{id}","/content/article/{id}"})
    public Map<String,Object> detail(@PathVariable String id,HttpServletRequest request) {
        require(request,"content:article:query"); return ok(queries.article(AdminContentQuery.id(id)));
    }
    @PutMapping({"/api/admin/content/article","/content/article"})
    public Map<String,Object> edit(@RequestBody AdminArticleInput input,HttpServletRequest request) {
        var p=require(request,"content:article:edit");
        if(input.id()==null || input.id()<=0) throw new ArticleFailure(400,"Invalid parameters");
        commands.adminEdit(p.userId(),input.id(),input.command(),input.isTop(),requestId(request)); return ok();
    }
    public record Status(String status) {}
    @PutMapping({"/api/admin/content/article/{id}/status","/content/article/{id}/status"})
    public Map<String,Object> status(@PathVariable String id,@RequestBody Status input,HttpServletRequest request) {
        var p=require(request,"content:article:edit");
        commands.adminStatus(p.userId(),AdminContentQuery.id(id),input.status(),requestId(request)); return ok();
    }
    @DeleteMapping({"/api/admin/content/article/{ids}","/content/article/{ids}"})
    public Map<String,Object> delete(@PathVariable String ids,HttpServletRequest request) {
        var p=require(request,"content:article:remove");
        String[] parts=ids.split(",",-1); if(parts.length==0 || parts.length>100) throw new ArticleFailure(400,"Invalid parameters");
        List<Long> normalized=new ArrayList<>(); for(String id:parts) normalized.add(AdminContentQuery.id(id));
        commands.adminDeleteBatch(p.userId(),normalized,requestId(request)); return ok();
    }
    @GetMapping({"/api/admin/content/category/list","/content/category/list"})
    public Map<String,Object> categories(@RequestParam(required=false) String pageNum,@RequestParam(required=false) String pageSize,HttpServletRequest request) {
        require(request,"content:category:list"); return ok(queries.categories(pageNum,pageSize));
    }
    @GetMapping({"/api/admin/content/category/listAllCategory","/content/category/listAllCategory"})
    public Map<String,Object> allCategories(HttpServletRequest request) {
        require(request,"content:category:list","content:article:add","content:article:edit","content:article:query");
        return ok(queries.allCategories());
    }
    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String,Object>> auth(AuthFailure e) {
        return ResponseEntity.status(e.status()).body(Map.of("code",e.status()==503?500:e.status(),"msg","Authentication failed"));
    }
    @ExceptionHandler(ArticleFailure.class)
    public ResponseEntity<Map<String,Object>> article(ArticleFailure e) {
        if(e.status()==400) return ResponseEntity.ok(Map.of("code",514,"msg","\u6587\u7ae0\u53c2\u6570\u4e0d\u5408\u6cd5"));
        if(e.status()==404) return ResponseEntity.ok(Map.of("code",513,"msg","\u6587\u7ae0\u4e0d\u5b58\u5728"));
        return ResponseEntity.status(e.status()).body(Map.of("code",e.status()==503?500:e.status(),"msg","Request failed"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed() { return ResponseEntity.badRequest().body(Map.of("code",400,"msg","Invalid request")); }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> system() { return ResponseEntity.internalServerError().body(Map.of("code",500,"msg","\u7cfb\u7edf\u9519\u8bef")); }
}
