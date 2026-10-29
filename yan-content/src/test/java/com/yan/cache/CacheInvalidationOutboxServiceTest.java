package com.yan.cache;

import com.yan.domain.entity.CacheInvalidationOutboxEvent;
import com.yan.mapper.CacheInvalidationOutboxMapper;
import com.yan.utils.RedisCache;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheInvalidationOutboxServiceTest {
    @Test
    void recordShouldUpsertCacheInvalidationTask() {
        CacheInvalidationOutboxMapper mapper = mock(CacheInvalidationOutboxMapper.class);
        RedisCache redisCache = mock(RedisCache.class);
        CacheInvalidationOutboxService service = new CacheInvalidationOutboxService(
                mapper, redisCache, 50, 30, 10, 300, 8);
        when(mapper.enqueue("CACHE_INVALIDATE_KEY:blog:article:detail:1",
                "blog:article:detail:1", 0, "redis down", 8)).thenReturn(1);

        service.record("blog:article:detail:1", false, "redis down");

        verify(mapper).enqueue("CACHE_INVALIDATE_KEY:blog:article:detail:1",
                "blog:article:detail:1", 0, "redis down", 8);
    }

    @Test
    void retryPendingShouldDeleteCacheAndMarkSucceeded() {
        CacheInvalidationOutboxMapper mapper = mock(CacheInvalidationOutboxMapper.class);
        RedisCache redisCache = mock(RedisCache.class);
        CacheInvalidationOutboxService service = new CacheInvalidationOutboxService(
                mapper, redisCache, 50, 30, 10, 300, 8);
        CacheInvalidationOutboxEvent event = new CacheInvalidationOutboxEvent();
        event.setId(9L);
        event.setCacheKey("blog:article:detail:1");
        event.setPatternFlag(0);
        when(mapper.selectPending(50)).thenReturn(List.of(event));
        when(mapper.claim(9L, 30)).thenReturn(1);

        service.retryPending();

        verify(mapper).recoverExpiredClaims();
        verify(mapper).markProcessingStarted(9L);
        verify(redisCache).deleteObject("blog:article:detail:1");
        verify(mapper).markSucceeded(9L);
    }
}
