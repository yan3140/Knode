package com.yan.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisFixedWindowRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
                return 0
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        if (limit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("rate limit and windowSeconds must be positive");
        }
        Long allowed = redisTemplate.execute(SCRIPT, List.of(key),
                Integer.toString(limit), Integer.toString(windowSeconds));
        return Long.valueOf(1L).equals(allowed);
    }
}
