package com.knode.search.cache;

import com.knode.search.consumer.config.SearchConsumerRuntimeConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SearchCacheInvalidator {
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT = createCompareAndDeleteScript();

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

    /**
     * Canonical cutover bypass owner token bound to a specific alias and monotonic target generation.
     * Shared across rebuild cutover, pre-switch rollback, and background cache invalidation compensation
     * so stale workers from an earlier generation can never delete a subsequent generation's bypass.
     */
    public static String cutoverBypassOwnerToken(String aliasName, long targetGeneration) {
        if (aliasName == null || aliasName.isBlank()) {
            throw new IllegalArgumentException("aliasName must be non-blank");
        }
        if (targetGeneration <= 0L) {
            throw new IllegalArgumentException("targetGeneration must be positive");
        }
        return aliasName.trim() + ":g" + targetGeneration;
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
     * Enables durable cross-instance search cache bypass BEFORE ES alias cutover begins,
     * tagged with the cutover owner token so only that cutover (or its compensation) may clear it.
     */
    public void enterCutoverBypass(String ownerToken) {
        if (redisTemplate == null) {
            return;
        }
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must be non-blank");
        }
        redisTemplate.opsForValue().set(cutoverBypassKey, ownerToken.trim());
    }

    /**
     * Atomically verifies cutover_bypass value equals expectedOwnerToken and deletes it via Redis Lua EVAL.
     * Returns true if the owned bypass marker was deleted, false if absent or owned by another cutover.
     */
    public boolean clearCutoverBypassIfOwned(String expectedOwnerToken) {
        if (redisTemplate == null) {
            return false;
        }
        if (expectedOwnerToken == null || expectedOwnerToken.isBlank()) {
            throw new IllegalArgumentException("expectedOwnerToken must be non-blank");
        }
        Long removed = redisTemplate.execute(
                COMPARE_AND_DELETE_SCRIPT,
                List.of(cutoverBypassKey),
                expectedOwnerToken.trim());
        return removed != null && removed > 0L;
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
     * after INCR has succeeded AND only if the bypass marker still belongs to expectedOwnerToken.
     */
    public long advanceEpochAndClearCutoverBypassIfOwned(String expectedOwnerToken) {
        if (redisTemplate == null) {
            return 0L;
        }
        long next = advanceEpoch();
        clearCutoverBypassIfOwned(expectedOwnerToken);
        return next;
    }

    private static DefaultRedisScript<Long> createCompareAndDeleteScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end");
        script.setResultType(Long.class);
        return script;
    }
}
