package com.yan.blog.controller;

import com.yan.domain.entity.Article;
import com.yan.domain.ResponseResult;
import com.yan.service.ArticleService;
import com.yan.domain.dto.ArticleDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import java.util.List;

@RestController
@RequestMapping({"/article", "/api/blog/article"})
public class ArticleController {

    @Autowired
    private ArticleService articleService;

    //    @GetMapping("/list")
//    public List<Article> test(){
//
//        return articleService.list();
//    }
    @GetMapping("/hotArticleList")
    public ResponseResult hotArticleList() {
        return articleService.hotArticleList();
    }

    @GetMapping("/articleList")
    public ResponseResult articleList(Integer pageNum, Integer pageSize, Long categoryId) {
        return articleService.articleList(pageNum, pageSize, categoryId);
    }

    @GetMapping("/{id}")
    public ResponseResult getArticleDetail(@PathVariable("id") Long id) {
        return articleService.getArticleDetail(id);
    }

    @PutMapping("/updateViewCount/{id}")
    public ResponseResult updateViewCount(@PathVariable Long id, HttpServletRequest request) {
        return articleService.updateViewCount(id, visitorFingerprint(request));
    }

    @PostMapping
    public ResponseResult publishArticle(@RequestBody ArticleDto articleDto) {
        articleDto.setIsTop("0");
        return articleService.addArticle(articleDto);
    }

    private String visitorFingerprint(HttpServletRequest request) {
        String source = request.getRemoteAddr() + "|" + request.getHeader("User-Agent");
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
