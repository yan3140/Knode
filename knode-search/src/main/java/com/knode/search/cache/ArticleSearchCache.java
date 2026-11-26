package com.knode.search.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.config.SearchCacheProperties;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.QueryBudget;
import com.knode.search.model.SearchCandidatePage;
import com.knode.search.model.SearchCandidateRow;
import com.knode.search.model.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class ArticleSearchCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleSearchCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SearchCacheProperties properties;
    private final SearchMetrics searchMetrics;
    private final String epochKey;
    private final String cutoverBypassKey;

    public ArticleSearchCache(
            @Qualifier("searchCacheRedisTemplate") ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            SearchCacheProperties properties,
            SearchMetrics searchMetrics,
            @Value("${knode.environment:local}") String environment) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.searchMetrics = searchMetrics;
        this.epochKey = SearchCacheInvalidator.epochKeyForEnvironment(environment);
        this.cutoverBypassKey = SearchCacheInvalidator.cutoverBypassKeyForEnvironment(environment);
    }

    public Optional<String> currentEpoch(QueryBudget budget) {
        if (redisTemplate == null || budget.isExpired()) {
            return Optional.empty();
        }
        try {
            String bypass = redisTemplate.opsForValue().get(cutoverBypassKey);
            if (bypass != null && !bypass.isEmpty()) {
                return Optional.empty();
            }
            String raw = redisTemplate.opsForValue().get(epochKey);
            if (raw == null || raw.isEmpty()) {
                return Optional.of("0");
            }
            if (!isNonNegativeInteger(raw)) {
                LOGGER.warn("event=search_cache_corrupt_epoch key={}", epochKey);
                searchMetrics.recordCacheFailure();
                return Optional.empty();
            }
            return Optional.of(raw);
        } catch (Exception cacheFailure) {
            searchMetrics.recordCacheFailure();
            LOGGER.warn("event=search_cache_epoch_read_failed key={}", epochKey, cacheFailure);
            return Optional.empty();
        }
    }

    public Optional<SearchCandidatePage> get(SearchQuery query, QueryBudget budget) {
        return get(query, "0", budget);
    }

    public Optional<SearchCandidatePage> get(SearchQuery query, String epoch, QueryBudget budget) {
        if (!query.cacheCandidate() || redisTemplate == null || epoch == null || budget.isExpired()) {
            return Optional.empty();
        }
        String key = resultKey(query, epoch);
        try {
            String payload = redisTemplate.opsForValue().get(key);
            if (payload == null) {
                searchMetrics.recordCacheMiss();
                return Optional.empty();
            }
            SearchCandidatePage page = objectMapper.readValue(payload, SearchCandidatePage.class);
            if (!isValidCachedPage(page)) {
                LOGGER.warn("event=search_cache_corrupt_payload key={}", key);
                searchMetrics.recordCacheFailure();
                return Optional.empty();
            }
            searchMetrics.recordCacheHit();
            return Optional.of(page);
        } catch (Exception cacheFailure) {
            searchMetrics.recordCacheFailure();
            LOGGER.warn("event=search_cache_read_failed key={}", key, cacheFailure);
            return Optional.empty();
        }
    }

    public void put(SearchQuery query, SearchCandidatePage page, QueryBudget budget) {
        putIfEpochUnchanged(query, "0", page, budget);
    }

    public void putIfEpochUnchanged(
            SearchQuery query,
            String expectedEpoch,
            SearchCandidatePage page,
            QueryBudget budget) {
        if (!query.cacheCandidate() || redisTemplate == null || expectedEpoch == null
                || page == null || budget.isExpired()) {
            return;
        }
        String key = resultKey(query, expectedEpoch);
        try {
            Optional<String> latestEpoch = currentEpoch(budget);
            if (latestEpoch.isEmpty() || !expectedEpoch.equals(latestEpoch.get())) {
                return;
            }
            if (!admitted(query)) {
                return;
            }
            int ttlSeconds = page.total() == 0L ? properties.getEmptyTtlSeconds() : properties.getResultTtlSeconds();
            String serialized = objectMapper.writeValueAsString(page);
            redisTemplate.opsForValue().set(
                    key,
                    serialized,
                    Duration.ofSeconds(ttlWithJitter(ttlSeconds)));
        } catch (Exception cacheFailure) {
            searchMetrics.recordCacheFailure();
            LOGGER.warn("event=search_cache_write_failed key={}", key, cacheFailure);
        }
    }

    public void evict(SearchQuery query) {
        evict(query, "0");
    }

    public void evict(SearchQuery query, String epoch) {
        if (!query.cacheCandidate() || redisTemplate == null || epoch == null) {
            return;
        }
        String key = resultKey(query, epoch);
        try {
            redisTemplate.delete(key);
        } catch (Exception cacheFailure) {
            searchMetrics.recordCacheFailure();
            LOGGER.warn("event=search_cache_evict_failed key={}", key, cacheFailure);
        }
    }

    public static String resultKey(SearchQuery query, String epoch) {
        return query.resultCacheKey() + ":e" + epoch;
    }

    private boolean admitted(SearchQuery query) {
        String admitKey = query.admissionCacheKey();
        Long count = redisTemplate.opsForValue().increment(admitKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(admitKey, Duration.ofSeconds(Math.max(1, properties.getAdmissionTtlSeconds())));
        }
        return count != null && count >= Math.max(1, properties.getAdmissionThreshold());
    }

    private int ttlWithJitter(int baseSeconds) {
        int jitter = Math.max(0, properties.getJitterSeconds());
        return Math.max(1, baseSeconds + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextInt(jitter + 1)));
    }

    private static boolean isNonNegativeInteger(String raw) {
        if (raw.length() > 19) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidCachedPage(SearchCandidatePage page) {
        if (page == null || page.rows() == null || page.total() < 0L) {
            return false;
        }
        for (SearchCandidateRow row : page.rows()) {
            if (row == null) {
                return false;
            }
        }
        return true;
    }
}
