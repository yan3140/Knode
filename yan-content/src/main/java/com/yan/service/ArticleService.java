package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.ResponseResult;
import com.yan.domain.dto.ArticleDto;
import com.yan.domain.entity.Article;
import com.yan.domain.ResponseResult;

import java.util.List;

public interface ArticleService extends IService<Article> {
    ResponseResult hotArticleList();


    ResponseResult articleList(Integer pageNum, Integer pageSize, Long categoryId);

    ResponseResult getArticleDetail(Long id);

    ResponseResult updateViewCount(Long id, String visitorFingerprint);

    ResponseResult addArticle(ArticleDto articleDto);

    ResponseResult listArticle(Integer pageNum, Integer pageSize, String title, String summary,
                               Long categoryId, String status);

    ResponseResult getArticle(Long id);

    ResponseResult updateArticle(ArticleDto articleDto);

    ResponseResult deleteArticle(List<Long> ids);

    ResponseResult updateArticleStatus(Long id, String status);
}
