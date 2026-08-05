package com.yan.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yan.domain.entity.Article;
import com.yan.domain.entity.ArticleTag;
import com.yan.domain.entity.Category;
import com.yan.domain.entity.Tag;
import com.yan.mapper.ArticleMapper;
import com.yan.mapper.ArticleTagMapper;
import com.yan.mapper.CategoryMapper;
import com.yan.mapper.TagMapper;
import com.yan.search.model.ArticleSearchDocument;
import com.yan.search.metrics.ArticleSearchMetrics;
import com.yan.search.repository.ArticleSearchRepository;
import com.yan.search.service.ArticleSearchCache;
import com.yan.search.service.SearchQueryPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleSearchServiceImplTest {

    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private ArticleTagMapper articleTagMapper;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private ArticleSearchRepository searchRepository;
    @Mock
    private ArticleSearchMetrics searchMetrics;
    @Mock
    private SearchQueryPlanner searchQueryPlanner;
    @Mock
    private ArticleSearchCache articleSearchCache;

    private ArticleSearchServiceImpl searchService;

    @BeforeEach
    void setUp() {
        searchService = new ArticleSearchServiceImpl(articleMapper, categoryMapper, articleTagMapper,
                tagMapper, searchRepository, searchMetrics, searchQueryPlanner, articleSearchCache);
    }

    @Test
    @SuppressWarnings("unchecked")
    void synchronizeOutboxShouldBulkBuildPublishedDocumentWithCategoryAndTags() throws Exception {
        Article article = article(1L, "搜索标题", "0");
        Category category = new Category();
        category.setId(2L);
        category.setName("Java");
        Tag tag = new Tag();
        tag.setId(3L);
        tag.setName("Spring Boot");

        when(articleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(article));
        when(categoryMapper.selectBatchIds(anyCollection())).thenReturn(List.of(category));
        when(articleTagMapper.selectList(any(Wrapper.class))).thenReturn(List.of(new ArticleTag(1L, 3L)));
        when(tagMapper.selectBatchIds(anyCollection())).thenReturn(List.of(tag));

        when(searchRepository.bulkSynchronize(anyList(), anyCollection())).thenReturn(Set.of());

        Set<Long> failedIds = searchService.synchronizeForOutbox(List.of(1L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArticleSearchDocument>> documentCaptor = ArgumentCaptor.forClass(List.class);
        verify(searchRepository).bulkSynchronize(documentCaptor.capture(), anyCollection());
        ArticleSearchDocument document = documentCaptor.getValue().get(0);
        assertThat(failedIds).isEmpty();
        assertThat(document.title()).isEqualTo("搜索标题");
        assertThat(document.categoryName()).isEqualTo("Java");
        assertThat(document.tagNames()).containsExactly("Spring Boot");
        verify(searchMetrics).recordSync(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void synchronizeOutboxShouldBulkDeleteDocumentWhenArticleIsNotPublished() throws Exception {
        when(articleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(searchRepository.bulkSynchronize(anyList(), anyCollection())).thenReturn(Set.of());

        Set<Long> failedIds = searchService.synchronizeForOutbox(List.of(2L));

        verify(searchRepository).bulkSynchronize(List.of(), List.of(2L));
        assertThat(failedIds).isEmpty();
        verify(searchMetrics).recordSync(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void synchronizeOutboxShouldReturnFailedIdsWhenElasticsearchIsUnavailable() throws Exception {
        Article article = article(3L, "失败补偿", "0");
        when(articleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(article));
        when(categoryMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(articleTagMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        doThrow(new IOException("Elasticsearch unavailable"))
                .when(searchRepository).bulkSynchronize(anyList(), anyCollection());

        Set<Long> failedIds = searchService.synchronizeForOutbox(List.of(3L));

        assertThat(failedIds).containsExactly(3L);
        verify(searchMetrics).recordSync(false);
    }

    private Article article(Long id, String title, String status) {
        Article article = new Article();
        article.setId(id);
        article.setTitle(title);
        article.setSummary("摘要");
        article.setContent("正文");
        article.setCategoryId(2L);
        article.setStatus(status);
        article.setViewCount(10L);
        article.setCreateTime(new Date(1_700_000_000_000L));
        return article;
    }
}
