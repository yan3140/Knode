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

    public ArticleSearchCache(
            @Qualifier("searchCacheRedisTemplate") ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            SearchCacheProperties properties,
            SearchMetrics searchMetrics) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.searchMetrics = searchMetrics;
    }

    public Optional<SearchCandidatePage> get(SearchQuery query, QueryBudget budget) {
        if (!query.cacheCandidate() || redisTemplate == null || budget.isExpired()) {
            return Optional.empty();
        }
        try {
            String payload = redisTemplate.opsForValue().get(query.resultCacheKey());
            if (payload == null) {
                searchMetrics.recordCacheMiss();
                return Optional.empty();
            }
            SearchCandidatePage page = objectMapper.readValue(payload, SearchCandidatePage.class);
            if (!isValidCachedPage(page)) {
                LOGGER.warn("event=search_cache_corrupt_payload key={}", query.resultCacheKey());
                searchMetrics.recordCacheFailure();
                return Optional.empty();
            }
            searchMetrics.recordCacheHit();
            return Optional.of(page);
        } catch (Exception cacheFailure) {
            searchMetrics.recordCacheFailure();
            LOGGER.warn("event=search_cache_read_failed key={}", query.resultCacheKey(), cacheFailure);
            return Optional.empty();
        }
    }

    public void put(SearchQuery query, SearchCandidatePage page, QueryBudget budget) {
        if (!query.cacheCandidate() || redisTemplate == null || page == null || budget.isExpired()) {
            return;
        }
        try {
            if (!admitted(query)) {
                return;
            }
            int ttlSeconds = page.total() == 0L ? properties.getEmptyTtlSeconds() : properties.getResultTtlSeconds();
            String serialized = objectMapper.writeValueAsString(page);
            redisTemplate.opsForValue().set(
                    query.resultCacheKey(),
                    serialized,
                    Duration.ofSeconds(ttlWithJitter(ttlSeconds)));
        } catch (Exception cacheFailure) {
            searchMetrics.recordCacheFailure();
            LOGGER.warn("event=search_cache_write_failed key={}", query.resultCacheKey(), cacheFailure);
        }
    }

    public void evict(SearchQuery query) {
        if (!query.cacheCandidate() || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(query.resultCacheKey());
        } catch (Exception cacheFailure) {
            searchMetrics.recordCacheFailure();
            LOGGER.warn("event=search_cache_evict_failed key={}", query.resultCacheKey(), cacheFailure);
        }
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
