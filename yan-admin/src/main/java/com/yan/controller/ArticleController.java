package com.yan.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.dto.ArticleDto;
import com.yan.service.ArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/content/article")
public class ArticleController {
    @Autowired
    private ArticleService articleService;

    @PostMapping
    public ResponseResult addArticle(@RequestBody ArticleDto articleDto){
        return articleService.addArticle(articleDto);
    }
}
