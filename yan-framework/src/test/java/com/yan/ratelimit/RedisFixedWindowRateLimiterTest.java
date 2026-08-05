package com.yan.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisFixedWindowRateLimiterTest {
    @Test
    void tryAcquireShouldAllowWhenRedisScriptReturnsOne() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("key")), eq("10"), eq("60")))
                .thenReturn(1L);

        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(redisTemplate);

        assertThat(limiter.tryAcquire("key", 10, 60)).isTrue();
    }

    @Test
    void tryAcquireShouldBlockWhenRedisScriptReturnsZero() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("key")), eq("10"), eq("60")))
                .thenReturn(0L);

        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(redisTemplate);

        assertThat(limiter.tryAcquire("key", 10, 60)).isFalse();
    }

    @Test
    void tryAcquireShouldRejectInvalidLimitConfiguration() {
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(mock(StringRedisTemplate.class));

        assertThatThrownBy(() -> limiter.tryAcquire("key", 0, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("key", 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
