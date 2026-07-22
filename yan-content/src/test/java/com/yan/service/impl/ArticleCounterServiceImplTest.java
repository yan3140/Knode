package com.yan.service.impl;

import com.yan.constants.CacheConstants;
import com.yan.domain.entity.Article;
import com.yan.mapper.ArticleMapper;
import com.yan.search.outbox.ArticleSearchOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleCounterServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ArticleMapper articleMapper;
    @Mock
    private HashOperations<String, String, String> hashOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private ArticleSearchOutboxService articleSearchOutboxService;

    private ArticleCounterServiceImpl counterService;

    @BeforeEach
    void setUp() {
        counterService = new ArticleCounterServiceImpl(redisTemplate, articleMapper, articleSearchOutboxService);
    }

    @Test
    void increaseViewCountCountsOnceWithinDedupWindow() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 0L);

        assertTrue(counterService.increaseViewCount(1L, "visitor-a"));
        assertFalse(counterService.increaseViewCount(1L, "visitor-a"));
        verifyNoInteractions(articleMapper);
    }

    @Test
    void increaseViewCountRejectsArticleOutsidePublishedSet() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-1L);

        assertFalse(counterService.increaseViewCount(999L, "visitor-a"));
        verifyNoInteractions(articleMapper);
    }

    @Test
    void initializeCountersMigratesHigherLegacyValueWithoutOverwritingNewCounter() {
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        Article article = new Article();
        article.setId(1L);
        article.setViewCount(10L);
        article.setStatus("0");
        when(articleMapper.selectArticleCounters()).thenReturn(List.of(article));
        when(hashOperations.get(CacheConstants.LEGACY_ARTICLE_VIEW_COUNT, "1")).thenReturn("12");
        when(hashOperations.putIfAbsent(CacheConstants.ARTICLE_VIEW_COUNT, "1", "12"))
                .thenReturn(true);

        counterService.initializeCounters();

        verify(hashOperations).putIfAbsent(CacheConstants.ARTICLE_VIEW_COUNT, "1", "12");
        verify(setOperations).add(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, "1");
        verify(setOperations).add(CacheConstants.ARTICLE_VIEW_COUNT_PUBLISHED, "1");
    }

    @Test
    void flushFailureKeepsDirtyMarkerForRetry() {
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        @SuppressWarnings("unchecked")
        Cursor<String> cursor = (Cursor<String>) org.mockito.Mockito.mock(Cursor.class);
        when(setOperations.scan(eq(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY), any()))
                .thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("1");
        when(hashOperations.multiGet(CacheConstants.ARTICLE_VIEW_COUNT, List.of("1")))
                .thenReturn(List.of("15"));
        Article article = new Article();
        article.setId(1L);
        when(articleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(article));
        when(articleMapper.updateViewCounts(Map.of(1L, 15L)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class,
                () -> counterService.flushDirtyCounters(CacheConstants.ARTICLE_VIEW_FLUSH_BATCH_SIZE));

        verify(setOperations, never()).remove(eq(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY), any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void successfulFlushPersistsSearchOutboxEvent() {
        when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        @SuppressWarnings("unchecked")
        Cursor<String> cursor = (Cursor<String>) org.mockito.Mockito.mock(Cursor.class);
        when(setOperations.scan(eq(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY), any()))
                .thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("1");
        when(hashOperations.multiGet(CacheConstants.ARTICLE_VIEW_COUNT, List.of("1")))
                .thenReturn(List.of("15"));
        Article article = new Article();
        article.setId(1L);
        when(articleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(article));
        when(articleMapper.updateViewCounts(Map.of(1L, 15L))).thenReturn(1);

        counterService.flushDirtyCounters(CacheConstants.ARTICLE_VIEW_FLUSH_BATCH_SIZE);

        verify(articleSearchOutboxService).enqueue(1L);
    }
}
