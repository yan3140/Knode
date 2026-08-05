package com.yan.search.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yan.search.config.SearchQueryProperties;
import com.yan.search.model.ArticleSearchPage;
import com.yan.search.model.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
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
    private final SearchQueryProperties properties;

    public ArticleSearchCache(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                              ObjectMapper objectMapper,
                              SearchQueryProperties properties) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<ArticleSearchPage> get(SearchQuery query) {
        if (!query.cacheCandidate() || redisTemplate == null) {
            return Optional.empty();
        }
        try {
            String payload = redisTemplate.opsForValue().get(query.cacheKey());
            if (payload == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(payload, ArticleSearchPage.class));
        } catch (RedisConnectionFailureException cacheUnavailable) {
            return Optional.empty();
        } catch (Exception invalidCache) {
            LOGGER.warn("event=article_search_cache_read_failed key={}", query.cacheKey(), invalidCache);
            return Optional.empty();
        }
    }

    public void put(SearchQuery query, ArticleSearchPage page) {
        if (!query.cacheCandidate() || redisTemplate == null || page == null || !admitted(query)) {
            return;
        }
        try {
            int ttlSeconds = page.total() == 0 ? properties.getCache().getEmptyTtlSeconds()
                    : properties.getCache().getResultTtlSeconds();
            redisTemplate.opsForValue().set(query.cacheKey(), objectMapper.writeValueAsString(page),
                    Duration.ofSeconds(ttlWithJitter(ttlSeconds)));
        } catch (RedisConnectionFailureException cacheUnavailable) {
            // 搜索缓存是优化项，不影响主查询。
        } catch (Exception cacheFailure) {
            LOGGER.warn("event=article_search_cache_write_failed key={}", query.cacheKey(), cacheFailure);
        }
    }

    private boolean admitted(SearchQuery query) {
        String key = query.cacheKey().replace("blog:search:result:", "blog:search:admit:");
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(properties.getCache().getAdmissionTtlSeconds()));
        }
        return count != null && count >= Math.max(1, properties.getCache().getAdmissionThreshold());
    }

    private int ttlWithJitter(int baseSeconds) {
        int jitter = Math.max(0, properties.getCache().getJitterSeconds());
        return Math.max(1, baseSeconds + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextInt(jitter + 1)));
    }
}
