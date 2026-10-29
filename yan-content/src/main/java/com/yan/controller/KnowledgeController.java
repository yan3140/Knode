package com.yan.controller;

import com.yan.domain.ResponseResult;
import com.yan.knowledge.application.KnowledgeBaseFacade;
import com.yan.knowledge.application.KnowledgeDocumentFacade;
import com.yan.knowledge.application.KnowledgePersonalDocumentFacade;
import com.yan.knowledge.application.KnowledgeQueryFacade;
import com.yan.knowledge.core.retrieval.RetrievalMode;
import com.yan.utils.SecurityUtils;
import java.util.Map;
import com.yan.knowledge.domain.KnowledgeBaseIds;
import com.yan.knowledge.domain.entity.KnowledgeBaseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/blog/knowledge")
public class KnowledgeController {
    private final KnowledgeBaseFacade bases; private final KnowledgeDocumentFacade documents; private final KnowledgePersonalDocumentFacade personal; private final KnowledgeQueryFacade queries;
    public KnowledgeController(KnowledgeBaseFacade bases, KnowledgeDocumentFacade documents, KnowledgePersonalDocumentFacade personal, KnowledgeQueryFacade queries) { this.bases=bases; this.documents=documents; this.personal=personal; this.queries=queries; }
    @GetMapping("/system") public ResponseResult system() { KnowledgeBaseEntity base=new KnowledgeBaseEntity(); base.setId(KnowledgeBaseIds.SYSTEM); base.setType("SYSTEM"); base.setName("系统知识库"); return ResponseResult.okResult(base); }
    @GetMapping("/mine") public ResponseResult mine() { return ResponseResult.okResult(bases.listPrivate(SecurityUtils.getUserId())); }
    @PostMapping public ResponseResult create(@RequestBody BaseRequest body) { return ResponseResult.okResult(bases.createPrivate(SecurityUtils.getUserId(), body.name(), body.description())); }
    @PutMapping("/{id}") public ResponseResult update(@PathVariable Long id, @RequestBody BaseRequest body) { bases.updatePrivate(SecurityUtils.getUserId(), id, body.name(), body.description()); return ResponseResult.okResult(); }
    @DeleteMapping("/{id}") public ResponseResult delete(@PathVariable Long id) { bases.deletePrivate(SecurityUtils.getUserId(), id); return ResponseResult.okResult(); }
    @PostMapping("/{id}/articles/{articleId}") public ResponseResult attach(@PathVariable Long id,@PathVariable Long articleId) { return ResponseResult.okResult(Map.of("attached", documents.attachPublishedArticle(SecurityUtils.getUserId(),id,articleId))); }
    @DeleteMapping("/{id}/documents/{documentId}/association") public ResponseResult detach(@PathVariable Long id,@PathVariable Long documentId) { return ResponseResult.okResult(Map.of("detached",documents.detachDocument(SecurityUtils.getUserId(),id,documentId))); }
    @PostMapping("/{id}/documents") public ResponseResult addDocument(@PathVariable Long id,@RequestBody DocumentRequest body) { return ResponseResult.okResult(Map.of("documentId",personal.create(SecurityUtils.getUserId(),id,body.title(),body.content()))); }
    @PutMapping("/{id}/documents/{documentId}") public ResponseResult updateDocument(@PathVariable Long id,@PathVariable Long documentId,@RequestBody DocumentRequest body) { return ResponseResult.okResult(Map.of("updated",personal.update(SecurityUtils.getUserId(),id,documentId,body.title(),body.content()))); }
    @DeleteMapping("/{id}/documents/{documentId}") public ResponseResult deleteDocument(@PathVariable Long id,@PathVariable Long documentId) { return ResponseResult.okResult(Map.of("deleted",personal.delete(SecurityUtils.getUserId(),id,documentId))); }
    @GetMapping("/{id}/documents/{documentId}/history") public ResponseResult history(@PathVariable Long id,@PathVariable Long documentId) { return ResponseResult.okResult(personal.history(SecurityUtils.getUserId(),id,documentId)); }
    @PostMapping("/{id}/query") public ResponseResult query(@PathVariable Long id,@RequestBody QueryRequest body) { return ResponseResult.okResult(queries.query(SecurityUtils.getUserId(),id,body.query(),body.mode()==null? RetrievalMode.AUTO:body.mode())); }
    @PostMapping("/query/{queryId}/feedback") public ResponseResult feedback(@PathVariable Long queryId,@RequestBody FeedbackRequest body) { queries.feedback(SecurityUtils.getUserId(),queryId,body.helpful(),body.comment()); return ResponseResult.okResult(); }
    public record BaseRequest(String name,String description) {} public record DocumentRequest(String title,String content) {} public record QueryRequest(String query, RetrievalMode mode) {} public record FeedbackRequest(boolean helpful,String comment) {}
}
