package com.yan.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yan.domain.ResponseResult;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Article;
import com.yan.domain.entity.ArticleTag;
import com.yan.domain.entity.Category;
import com.yan.domain.entity.Tag;
import com.yan.domain.vo.PageVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.ArticleMapper;
import com.yan.mapper.ArticleTagMapper;
import com.yan.mapper.CategoryMapper;
import com.yan.mapper.TagMapper;
import com.yan.search.model.ArticleSearchDocument;
import com.yan.search.model.ArticleSearchPage;
import com.yan.search.metrics.ArticleSearchMetrics;
import com.yan.search.repository.ArticleSearchRepository;
import com.yan.search.model.SearchQuery;
import com.yan.search.service.ArticleSearchCache;
import com.yan.search.service.ArticleSearchService;
import com.yan.search.service.SearchQueryPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ArticleSearchServiceImpl implements ArticleSearchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleSearchServiceImpl.class);

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;
    private final ArticleSearchRepository searchRepository;
    private final ArticleSearchMetrics searchMetrics;
    private final SearchQueryPlanner searchQueryPlanner;
    private final ArticleSearchCache articleSearchCache;

    public ArticleSearchServiceImpl(ArticleMapper articleMapper,
                                    CategoryMapper categoryMapper,
                                    ArticleTagMapper articleTagMapper,
                                    TagMapper tagMapper,
                                    ArticleSearchRepository searchRepository,
                                    ArticleSearchMetrics searchMetrics,
                                    SearchQueryPlanner searchQueryPlanner,
                                    ArticleSearchCache articleSearchCache) {
        this.articleMapper = articleMapper;
        this.categoryMapper = categoryMapper;
        this.articleTagMapper = articleTagMapper;
        this.tagMapper = tagMapper;
        this.searchRepository = searchRepository;
        this.searchMetrics = searchMetrics;
        this.searchQueryPlanner = searchQueryPlanner;
        this.articleSearchCache = articleSearchCache;
    }

    @Override
    public ResponseResult search(String keyword, Integer pageNum, Integer pageSize, String sort) {
        SearchQuery query = searchQueryPlanner.plan(keyword, pageNum, pageSize, sort);
        long start = System.nanoTime();
        try {
            ArticleSearchPage page = articleSearchCache.get(query)
                    .orElseGet(() -> searchAndCache(query));
            searchMetrics.recordQuery(true, System.nanoTime() - start);
            return ResponseResult.okResult(new PageVo(page.rows(), page.total()));
        } catch (RuntimeException searchFailure) {
            searchMetrics.recordQuery(false, System.nanoTime() - start);
            LOGGER.error("event=article_search_failed keyword={}", keyword, searchFailure);
            throw new SystemException(AppHttpCodeEnum.SEARCH_SERVICE_UNAVAILABLE);
        }
    }

    private ArticleSearchPage searchAndCache(SearchQuery query) {
        try {
            ArticleSearchPage page = searchRepository.search(query);
            articleSearchCache.put(query, page);
            return page;
        } catch (IOException searchFailure) {
            throw new IllegalStateException(searchFailure);
        }
    }

    @Override
    public ResponseResult rebuildIndex() {
        List<Article> articles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .eq(Article::getStatus, Integer.toString(SystemConstants.ARTICLE_STATUS_NORMAL)));
        List<ArticleSearchDocument> documents = buildDocuments(articles);
        try {
            searchRepository.rebuild(documents);
            searchMetrics.recordRebuild(true);
            return ResponseResult.okResult(Map.of("indexed", documents.size()));
        } catch (IOException | RuntimeException rebuildFailure) {
            searchMetrics.recordRebuild(false);
            LOGGER.error("event=search_index_rebuild_failed articleCount={}", documents.size(), rebuildFailure);
            throw new SystemException(AppHttpCodeEnum.SEARCH_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public ResponseResult synchronizeArticle(Long articleId) {
        if (articleId == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        if (!synchronizeForOutbox(List.of(articleId)).isEmpty()) {
            throw new SystemException(AppHttpCodeEnum.SEARCH_SERVICE_UNAVAILABLE);
        }
        return ResponseResult.okResult();
    }

    @Override
    public Set<Long> synchronizeForOutbox(Collection<Long> articleIds) {
        Set<Long> ids = articleIds == null ? Collections.emptySet() : articleIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            List<Article> articles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                    .in(Article::getId, ids)
                    .eq(Article::getStatus, Integer.toString(SystemConstants.ARTICLE_STATUS_NORMAL)));
            List<ArticleSearchDocument> documents = buildDocuments(articles);
            Set<Long> documentIds = documents.stream()
                    .map(ArticleSearchDocument::id)
                    .collect(Collectors.toSet());
            Set<Long> failedIds = searchRepository.bulkSynchronize(documents,
                    ids.stream().filter(id -> !documentIds.contains(id)).toList());
            searchMetrics.recordSync(true);
            return failedIds;
        } catch (IOException | RuntimeException syncFailure) {
            searchMetrics.recordSync(false);
            LOGGER.warn("event=search_index_bulk_sync_failed articleCount={}", ids.size(), syncFailure);
            return ids;
        }
    }

    List<ArticleSearchDocument> buildDocuments(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> articleIds = articles.stream().map(Article::getId).collect(Collectors.toSet());
        Set<Long> categoryIds = articles.stream().map(Article::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryIds.isEmpty() ? Collections.emptyMap()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        List<ArticleTag> relations = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                .in(ArticleTag::getArticleId, articleIds));
        Set<Long> tagIds = relations.stream().map(ArticleTag::getTagId).collect(Collectors.toSet());
        Map<Long, String> tagNames = tagIds.isEmpty() ? Collections.emptyMap()
                : tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getName));
        Map<Long, List<String>> tagNamesByArticle = new LinkedHashMap<>();
        for (ArticleTag relation : relations) {
            String tagName = tagNames.get(relation.getTagId());
            if (tagName != null) {
                tagNamesByArticle.computeIfAbsent(relation.getArticleId(), ignored -> new ArrayList<>())
                        .add(tagName);
            }
        }

        return articles.stream().map(article -> new ArticleSearchDocument(
                article.getId(),
                article.getTitle(),
                article.getSummary(),
                article.getContent(),
                article.getCategoryId(),
                categoryNames.get(article.getCategoryId()),
                tagNamesByArticle.getOrDefault(article.getId(), Collections.emptyList()),
                article.getThumbnail(),
                article.getViewCount() == null ? 0L : article.getViewCount(),
                article.getCreateTime() == null ? null : article.getCreateTime().getTime()
        )).toList();
    }

}
