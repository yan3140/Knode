package com.knode.community.auth;

import com.knode.auth.AuthFailure;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public final class RedisSessionReader implements SessionReader {
    private final ReactiveStringRedisTemplate redis;
    private final String prefix;

    public RedisSessionReader(ReactiveStringRedisTemplate redis, AuthConfiguration.Settings settings) {
        this.redis = redis;
        this.prefix = "knode:" + settings.environment() + ":v1:auth:session:";
    }

    @Override
    public Optional<String> read(String sid) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(prefix + sid)
                    .timeout(Duration.ofMillis(800)).block(Duration.ofMillis(1000)));
        } catch (RuntimeException failure) {
            throw new AuthFailure(503);
        }
    }
}
