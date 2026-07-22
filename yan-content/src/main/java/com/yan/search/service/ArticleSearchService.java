package com.yan.search.service;

import com.yan.domain.ResponseResult;

import java.util.Collection;
import java.util.Set;

public interface ArticleSearchService {
    ResponseResult search(String keyword, Integer pageNum, Integer pageSize, String sort);

    ResponseResult rebuildIndex();

    ResponseResult synchronizeArticle(Long articleId);

    Set<Long> synchronizeForOutbox(Collection<Long> articleIds);
}
