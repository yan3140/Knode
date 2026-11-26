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
    private final String cutoverBypassKey;

    public SearchCacheInvalidator(
            @Qualifier("searchCacheRedisTemplate") ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${knode.environment:local}") String environment) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.epochKey = epochKeyForEnvironment(environment);
        this.cutoverBypassKey = cutoverBypassKeyForEnvironment(environment);
    }

    public static String epochKeyForEnvironment(String rawEnvironment) {
        String env = SearchConsumerRuntimeConfig.sanitizeEnvironment(rawEnvironment);
        return "knode:" + env + ":v1:search:epoch";
    }

    public static String cutoverBypassKeyForEnvironment(String rawEnvironment) {
        String env = SearchConsumerRuntimeConfig.sanitizeEnvironment(rawEnvironment);
        return "knode:" + env + ":v1:search:cutover_bypass";
    }

    public boolean isCacheEnabled() {
        return redisTemplate != null;
    }

    public String epochKey() {
        return epochKey;
    }

    public String cutoverBypassKey() {
        return cutoverBypassKey;
    }

    /**
     * Enables durable cross-instance search cache bypass BEFORE ES alias cutover begins.
     * Any query instance (including freshly restarted instances) seeing this marker skips
     * stale pre-cutover Redis cache reads and writebacks and queries ES via the active alias
     * directly until epoch invalidation succeeds and clears the marker.
     */
    public void enterCutoverBypass(String marker) {
        if (redisTemplate == null) {
            return;
        }
        String value = (marker == null || marker.isBlank()) ? "1" : marker.trim();
        redisTemplate.opsForValue().set(cutoverBypassKey, value);
    }

    public void clearCutoverBypass() {
        if (redisTemplate == null) {
            return;
        }
        redisTemplate.delete(cutoverBypassKey);
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

    /**
     * Advances the search cache epoch via Redis INCR and ONLY clears the cutover bypass marker
     * after INCR has succeeded. If INCR fails, the bypass marker remains set so all query
     * instances continue isolating post-cutover reads from stale pre-cutover cache entries.
     */
    public long advanceEpochAndClearCutoverBypass() {
        if (redisTemplate == null) {
            return 0L;
        }
        long next = advanceEpoch();
        redisTemplate.delete(cutoverBypassKey);
        return next;
    }
}
