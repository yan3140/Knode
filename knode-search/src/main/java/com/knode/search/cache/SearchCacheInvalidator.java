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
    private static final DefaultRedisScript<Long> MONOTONIC_SET_BYPASS_SCRIPT = createMonotonicSetBypassScript();

    private final StringRedisTemplate redisTemplate;
    private final String epochKey;
    private final String cutoverBypassKey;
    private final String cutoverBypassWatermarkKey;

    public SearchCacheInvalidator(
            @Qualifier("searchCacheRedisTemplate") ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${knode.environment:local}") String environment) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.epochKey = epochKeyForEnvironment(environment);
        this.cutoverBypassKey = cutoverBypassKeyForEnvironment(environment);
        this.cutoverBypassWatermarkKey = cutoverBypassWatermarkKeyForEnvironment(environment);
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
     * Durable monotonic high-water mark paired with cutover_bypass so stale delayed writes from an
     * earlier attempt (e.g. g2:a1) remain atomically rejected even after a newer attempt (e.g. g2:a2)
     * has completed and deleted cutover_bypass.
     */
    public static String cutoverBypassWatermarkKeyForEnvironment(String rawEnvironment) {
        String env = SearchConsumerRuntimeConfig.sanitizeEnvironment(rawEnvironment);
        return "knode:" + env + ":v1:search:cutover_bypass_watermark";
    }

    /**
     * Canonical attempt-scoped cutover bypass owner token bound to alias, monotonic target generation,
     * and monotonic switch attempt counter within that candidate's rebuild job.
     * Prevents both cross-generation stale compensations and delayed pre-switch rollback cleanups
     * from deleting a subsequent attempt's active bypass marker.
     */
    public static String cutoverBypassOwnerToken(String aliasName, long targetGeneration, long switchAttempt) {
        if (aliasName == null || aliasName.isBlank()) {
            throw new IllegalArgumentException("aliasName must be non-blank");
        }
        if (targetGeneration <= 0L) {
            throw new IllegalArgumentException("targetGeneration must be positive");
        }
        if (switchAttempt <= 0L) {
            throw new IllegalArgumentException("switchAttempt must be positive");
        }
        return aliasName.trim() + ":g" + targetGeneration + ":a" + switchAttempt;
    }

    /**
     * Legacy SEARCH-03-R2 generation-only owner token retained solely as a fallback for
     * upgrading existing PENDING generation rows that have a null active_cutover_bypass_owner.
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

    public String cutoverBypassWatermarkKey() {
        return cutoverBypassWatermarkKey;
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
     * Atomically activates cutover_bypass and advances the durable cutover_bypass_watermark for
     * (targetGeneration, switchAttempt) ONLY if no newer attempt/generation has already been
     * observed (active or cleared) and this exact attempt has not already been cleared.
     * Prevents delayed stale-instance writes (e.g. g2:a1 arriving after g2:a2 completed and cleared
     * cutover_bypass) from re-enabling bypass across instances.
     */
    public void enterCutoverBypass(String ownerToken, long targetGeneration, long switchAttempt) {
        if (redisTemplate == null) {
            return;
        }
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must be non-blank");
        }
        if (targetGeneration <= 0L || switchAttempt <= 0L) {
            throw new IllegalArgumentException("targetGeneration and switchAttempt must be positive");
        }
        Long written = redisTemplate.execute(
                MONOTONIC_SET_BYPASS_SCRIPT,
                List.of(cutoverBypassKey, cutoverBypassWatermarkKey),
                ownerToken.trim(),
                Long.toString(targetGeneration),
                Long.toString(switchAttempt));
        if (written == null || written <= 0L) {
            throw new IllegalStateException("Stale cutover_bypass owner write rejected: " + ownerToken.trim());
        }
    }

    /**
     * Atomically verifies cutover_bypass value equals expectedOwnerToken, deletes it via Redis Lua EVAL,
     * and records a monotonic CLEARED watermark so delayed writes from this or any earlier attempt cannot
     * re-enable cutover_bypass after completion or rollback.
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
                List.of(cutoverBypassKey, cutoverBypassWatermarkKey),
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
                "local eg, ea = string.match(ARGV[1], ':g(%d+):a(%d+)$') "
                        + "if not eg then eg = string.match(ARGV[1], ':g(%d+)$') ea = '0' end "
                        + "local cur = redis.call('GET', KEYS[1]) "
                        + "local deleted = 0 "
                        + "if cur == ARGV[1] then "
                        + "deleted = redis.call('DEL', KEYS[1]) "
                        + "elseif cur then "
                        + "return 0 "
                        + "end "
                        + "if eg then "
                        + "local egn = tonumber(eg) local ean = tonumber(ea) "
                        + "local hwm = redis.call('GET', KEYS[2]) "
                        + "local shouldUpdate = true "
                        + "if hwm then "
                        + "local hg, ha = string.match(hwm, '^(%d+):(%d+):[A-Z]+$') "
                        + "if hg then "
                        + "local hgn = tonumber(hg) local han = tonumber(ha) "
                        + "if egn < hgn or (egn == hgn and ean < han) then shouldUpdate = false end "
                        + "end "
                        + "end "
                        + "if shouldUpdate then redis.call('SET', KEYS[2], eg .. ':' .. ea .. ':CLEARED') end "
                        + "end "
                        + "return deleted");
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<Long> createMonotonicSetBypassScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "local ng = tonumber(ARGV[2]) local na = tonumber(ARGV[3]) "
                        + "local hwm = redis.call('GET', KEYS[2]) "
                        + "if hwm then "
                        + "local hg, ha, hs = string.match(hwm, '^(%d+):(%d+):([A-Z]+)$') "
                        + "if hg then "
                        + "local hgn = tonumber(hg) local han = tonumber(ha) "
                        + "if ng < hgn or (ng == hgn and na < han) then return 0 end "
                        + "if ng == hgn and na == han and hs ~= 'ACTIVE' then return 0 end "
                        + "end "
                        + "end "
                        + "local cur = redis.call('GET', KEYS[1]) "
                        + "if cur then "
                        + "local cg, ca = string.match(cur, ':g(%d+):a(%d+)$') "
                        + "if not cg then cg = string.match(cur, ':g(%d+)$') ca = '0' end "
                        + "if cg then "
                        + "local cgn = tonumber(cg) local can = tonumber(ca) "
                        + "if ng < cgn or (ng == cgn and na < can) then return 0 end "
                        + "end "
                        + "end "
                        + "redis.call('SET', KEYS[1], ARGV[1]) "
                        + "redis.call('SET', KEYS[2], ARGV[2] .. ':' .. ARGV[3] .. ':ACTIVE') "
                        + "return 1");
        script.setResultType(Long.class);
        return script;
    }
}
