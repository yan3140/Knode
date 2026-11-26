package com.knode.search.cache;

import com.knode.search.consumer.config.SearchConsumerRuntimeConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SearchCacheInvalidator {
    private final StringRedisTemplate redisTemplate;
    private final String epochKey;

    public SearchCacheInvalidator(
            @Qualifier("searchCacheRedisTemplate") ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${knode.environment:local}") String environment) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.epochKey = epochKeyForEnvironment(environment);
    }

    public static String epochKeyForEnvironment(String rawEnvironment) {
        String env = SearchConsumerRuntimeConfig.sanitizeEnvironment(rawEnvironment);
        return "knode:" + env + ":v1:search:epoch";
    }

    public boolean isCacheEnabled() {
        return redisTemplate != null;
    }

    public String epochKey() {
        return epochKey;
    }

    public long advanceEpoch() {
        if (redisTemplate == null) {
            return 0L;
        }
        Long next = redisTemplate.opsForValue().increment(epochKey);
        if (next == null || next <= 0L) {
            throw new IllegalStateException("Unexpected null or non-positive epoch returned by Redis INCR");
        }
        return next;
    }
}
