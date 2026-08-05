package com.yan.utils;

import com.yan.cache.CacheInvalidationRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheClientTest {
    @Test
    void returnsCachedValueWithoutQueryingDatabase() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject("key")).thenReturn("cached");
        AtomicInteger databaseCalls = new AtomicInteger();

        String value = new CacheClient(redisCache)
                .queryWithMutex("key", 10, 5, () -> {
                    databaseCalls.incrementAndGet();
                    return "database";
                });

        assertThat(value).isEqualTo("cached");
        assertThat(databaseCalls).hasValue(0);
        verify(redisCache, never()).setCacheObjectIfAbsent(
                eq("lock:key"), eq("1"), anyInt(), eq(TimeUnit.SECONDS));
    }

    @Test
    void rebuildsAndCachesValueWhileHoldingMutex() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject("key")).thenReturn(null);
        when(redisCache.setCacheObjectIfAbsent("lock:key", "1", 10, TimeUnit.SECONDS)).thenReturn(true);

        String value = new CacheClient(redisCache)
                .queryWithMutex("key", 10, 0, () -> "database");

        assertThat(value).isEqualTo("database");
        verify(redisCache).setCacheObject("key", "database", 10, TimeUnit.MINUTES);
        verify(redisCache).deleteObject("lock:key");
    }

    @Test
    void fallsBackToDatabaseWhenRedisReadFails() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject("key")).thenThrow(new RuntimeException("redis down"));

        String value = new CacheClient(redisCache)
                .queryWithMutex("key", 10, 5, () -> "database");

        assertThat(value).isEqualTo("database");
    }

    @Test
    void recordsInvalidationWhenDeleteFails() {
        RedisCache redisCache = mock(RedisCache.class);
        CacheInvalidationRecorder recorder = mock(CacheInvalidationRecorder.class);
        ObjectProvider<CacheInvalidationRecorder> recorderProvider = mock(ObjectProvider.class);
        when(recorderProvider.getIfAvailable()).thenReturn(recorder);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(redisCache).deleteObject("article:1");

        new CacheClient(redisCache, recorderProvider).delete("article:1");

        verify(recorder).record("article:1", false, "redis down");
        verifyNoMoreInteractions(recorder);
    }
}
