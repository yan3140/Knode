package com.yan.admin.controller;

import com.yan.domain.ResponseResult;
import com.yan.search.outbox.ArticleSearchOutboxService;
import com.yan.search.service.ArticleSearchService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("adminSearchController")
@RequestMapping({"/content/search", "/api/admin/content/search"})
public class SearchController {
    private final ArticleSearchService articleSearchService;
    private final ArticleSearchOutboxService articleSearchOutboxService;

    public SearchController(ArticleSearchService articleSearchService,
                            ArticleSearchOutboxService articleSearchOutboxService) {
        this.articleSearchService = articleSearchService;
        this.articleSearchOutboxService = articleSearchOutboxService;
    }

    @PostMapping("/rebuild")
    public ResponseResult rebuild() {
        return articleSearchService.rebuildIndex();
    }

    @PostMapping("/retry")
    public ResponseResult retry() {
        return articleSearchOutboxService.replayPending();
    }

    @PostMapping("/sync/{articleId}")
    public ResponseResult synchronizeArticle(@PathVariable Long articleId) {
        return articleSearchService.synchronizeArticle(articleId);
    }
}
