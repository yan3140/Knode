package com.yan.search.model;

import com.yan.domain.vo.SearchArticleVo;

import java.util.List;

public record ArticleSearchPage(List<SearchArticleVo> rows, long total) {
}
