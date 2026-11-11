package com.knode.content.metadata;

import com.knode.auth.AuthFailure;
import com.knode.content.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
public class MetadataController {
    private final AdminGuard guard;
    private final MetadataQuery query;
    private final TagService tags;
    private final LinkService links;
    public MetadataController(AdminGuard guard,MetadataQuery query,TagService tags,LinkService links) {
        this.guard=guard; this.query=query; this.tags=tags; this.links=links;
    }
    private static Map<String,Object> ok() { return Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f"); }
    private static Map<String,Object> ok(Object value) {
        return value==null?ok():Map.of("code",200,"msg","\u64cd\u4f5c\u6210\u529f","data",value);
    }
    @GetMapping({"/api/admin/content/tag/list","/content/tag/list"})
    public Map<String,Object> tags(@RequestParam(required=false) String pageNum,@RequestParam(required=false) String pageSize,
            @RequestParam(required=false) String name,@RequestParam(required=false) String remark,HttpServletRequest r) {
        guard.require(r,"content:tag:list"); return ok(query.tags(pageNum,pageSize,name,remark));
    }
    @GetMapping({"/api/admin/content/tag/listAllTag","/content/tag/listAllTag"})
    public Map<String,Object> allTags(HttpServletRequest r) {
        guard.require(r,"content:tag:list","content:article:add","content:article:edit","content:article:query"); return ok(query.allTags());
    }
    @GetMapping({"/api/admin/content/tag/{id}","/content/tag/{id}"})
    public Map<String,Object> tag(@PathVariable String id,HttpServletRequest r) {
        guard.require(r,"content:tag:query"); return ok(query.tag(MetadataQuery.id(id)));
    }
    @PostMapping({"/api/admin/content/tag","/content/tag"})
    public Map<String,Object> createTag(@RequestBody TagInput in,HttpServletRequest r) {
        var p=guard.require(r,"content:tag:add"); tags.create(p.userId(),in); return ok();
    }
    @PutMapping({"/api/admin/content/tag","/content/tag"})
    public Map<String,Object> updateTag(@RequestBody TagInput in,HttpServletRequest r) {
        var p=guard.require(r,"content:tag:edit"); tags.update(p.userId(),in); return ok();
    }
    @DeleteMapping({"/api/admin/content/tag/{ids}","/content/tag/{ids}"})
    public Map<String,Object> deleteTags(@PathVariable String ids,HttpServletRequest r) {
        var p=guard.require(r,"content:tag:remove"); tags.delete(p.userId(),MetadataQuery.ids(ids)); return ok();
    }
    @GetMapping({"/api/admin/content/link/list","/content/link/list"})
    public Map<String,Object> links(@RequestParam(required=false) String pageNum,@RequestParam(required=false) String pageSize,
            @RequestParam(required=false) String name,@RequestParam(required=false) String status,HttpServletRequest r) {
        guard.require(r,"content:link:list"); return ok(query.links(pageNum,pageSize,name,status));
    }
    @GetMapping({"/api/admin/content/link/{id}","/content/link/{id}"})
    public Map<String,Object> link(@PathVariable String id,HttpServletRequest r) {
        guard.require(r,"content:link:query"); return ok(query.link(MetadataQuery.id(id)));
    }
    @PostMapping({"/api/admin/content/link","/content/link"})
    public Map<String,Object> createLink(@RequestBody LinkInput in,HttpServletRequest r) {
        var p=guard.require(r,"content:link:add"); links.create(p.userId(),in); return ok();
    }
    @PutMapping({"/api/admin/content/link","/content/link"})
    public Map<String,Object> updateLink(@RequestBody LinkInput in,HttpServletRequest r) {
        var p=guard.require(r,"content:link:edit"); links.update(p.userId(),in); return ok();
    }
    @PutMapping({"/api/admin/content/link/changeLinkStatus","/content/link/changeLinkStatus"})
    public Map<String,Object> status(@RequestBody LinkInput in,HttpServletRequest r) {
        var p=guard.require(r,"content:link:edit"); links.status(p.userId(),in==null?null:in.id(),in==null?null:in.status()); return ok();
    }
    @DeleteMapping({"/api/admin/content/link/{ids}","/content/link/{ids}"})
    public Map<String,Object> deleteLinks(@PathVariable String ids,HttpServletRequest r) {
        var p=guard.require(r,"content:link:remove"); links.delete(p.userId(),MetadataQuery.ids(ids)); return ok();
    }
    @GetMapping({"/api/blog/link/getAllLink","/link/getAllLink"})
    public Map<String,Object> publicLinks() { return ok(query.publicLinks()); }

    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String,Object>> auth(AuthFailure e) {
        return ResponseEntity.status(e.status()).body(Map.of("code",e.status()==503?500:e.status(),"msg","Authentication failed"));
    }
    @ExceptionHandler(MetadataFailure.class)
    public ResponseEntity<Map<String,Object>> metadata(MetadataFailure e) {
        return ResponseEntity.ok(Map.of("code",e.code(),"msg",e.code()==506?"\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a":"\u53c2\u6570\u4e0d\u5408\u6cd5"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformed() { return ResponseEntity.badRequest().body(Map.of("code",400,"msg","Invalid request")); }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> system() { return ResponseEntity.internalServerError().body(Map.of("code",500,"msg","\u7cfb\u7edf\u9519\u8bef")); }
}
