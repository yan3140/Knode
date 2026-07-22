package com.yan.admin.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.dto.ArticleDto;
import com.yan.domain.dto.ArticleStatusDto;
import com.yan.service.ArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController("adminArticleController")
@RequestMapping({"/content/article", "/api/admin/content/article"})
public class ArticleController {
    @Autowired
    private ArticleService articleService;

    @PostMapping
    public ResponseResult addArticle(@RequestBody ArticleDto articleDto){
        return articleService.addArticle(articleDto);
    }

    @GetMapping("/list")
    public ResponseResult listArticle(Integer pageNum, Integer pageSize, String title, String summary,
                                      Long categoryId, String status) {
        return articleService.listArticle(pageNum, pageSize, title, summary, categoryId, status);
    }

    @GetMapping("/{id}")
    public ResponseResult getArticle(@PathVariable Long id) {
        return articleService.getArticle(id);
    }

    @PutMapping
    public ResponseResult updateArticle(@RequestBody ArticleDto articleDto) {
        return articleService.updateArticle(articleDto);
    }

    @DeleteMapping("/{ids}")
    public ResponseResult deleteArticle(@PathVariable List<Long> ids) {
        return articleService.deleteArticle(ids);
    }

    @PutMapping("/{id}/status")
    public ResponseResult updateArticleStatus(@PathVariable Long id,
                                              @RequestBody ArticleStatusDto articleStatusDto) {
        return articleService.updateArticleStatus(id, articleStatusDto.getStatus());
    }
}
