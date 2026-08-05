package com.yan.blog.controller;

import com.yan.domain.ResponseResult;
import com.yan.ratelimit.RateLimit;
import com.yan.ratelimit.RateLimitTarget;
import com.yan.search.service.ArticleSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/search", "/api/blog/search"})
public class SearchController {
    private final ArticleSearchService articleSearchService;

    public SearchController(ArticleSearchService articleSearchService) {
        this.articleSearchService = articleSearchService;
    }

    @GetMapping
    @RateLimit(name = "search:ip", target = RateLimitTarget.IP, limit = 120, windowSeconds = 60)
    @RateLimit(name = "search:keyword", target = RateLimitTarget.IP_AND_PARAM, paramName = "keyword",
            limit = 30, windowSeconds = 60)
    public ResponseResult search(String keyword, Integer pageNum, Integer pageSize, String sort) {
        return articleSearchService.search(keyword, pageNum, pageSize, sort);
    }
}
