package com.yan.service.impl;

import com.yan.constants.CacheConstants;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Article;
import com.yan.mapper.ArticleMapper;
import com.yan.service.ArticleCounterService;
import com.yan.search.outbox.ArticleSearchOutboxService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ArticleCounterServiceImpl implements ArticleCounterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleCounterServiceImpl.class);

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 0 then
                return -1
            end
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
                return -2
            end
            if not redis.call('SET', KEYS[4], '1', 'NX', 'EX', ARGV[2]) then
                return 0
            end
            redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
            redis.call('SADD', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REMOVE_DIRTY_IF_UNCHANGED_SCRIPT =
            new DefaultRedisScript<>("""
                    local current = redis.call('HGET', KEYS[1], ARGV[1])
                    if current and current == ARGV[2] then
                        return redis.call('SREM', KEYS[2], ARGV[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ArticleMapper articleMapper;
    private final ArticleSearchOutboxService articleSearchOutboxService;
    private final TransactionTemplate transactionTemplate;

    public ArticleCounterServiceImpl(StringRedisTemplate redisTemplate, ArticleMapper articleMapper,
                                     ArticleSearchOutboxService articleSearchOutboxService,
                                     PlatformTransactionManager transactionManager) {
        this.redisTemplate = redisTemplate;
        this.articleMapper = articleMapper;
        this.articleSearchOutboxService = articleSearchOutboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean increaseViewCount(Long articleId, String visitorFingerprint) {
        if (articleId == null || !StringUtils.hasText(visitorFingerprint)) {
            return false;
        }
        Long result = executeIncrement(articleId, visitorFingerprint);
        if (Objects.equals(result, -2L)) {
            synchronizeArticle(articleId);
            result = executeIncrement(articleId, visitorFingerprint);
        }
        return Objects.equals(result, 1L);
    }

    @Override
    public Long getViewCount(Long articleId, Long databaseValue) {
        if (articleId == null) {
            return databaseValue;
        }
        try {
            String value = redisTemplate.<String, String>opsForHash()
                    .get(CacheConstants.ARTICLE_VIEW_COUNT, articleId.toString());
            return value == null ? databaseValue : Long.parseLong(value);
        } catch (RuntimeException redisFailure) {
            LOGGER.warn("读取文章浏览量失败，回退数据库值，articleId={}", articleId, redisFailure);
            return databaseValue;
        }
    }

    @Override
    public int flushDirtyCounters(int batchSize) {
        return transactionTemplate.execute(status -> flushInTransaction(batchSize));
    }

    private int flushInTransaction(int batchSize) {
        int safeBatchSize = Math.max(1, batchSize);
        List<String> dirtyIds = scanDirtyIds(safeBatchSize);
        if (dirtyIds.isEmpty()) {
            return 0;
        }

        List<String> values = redisTemplate.<String, String>opsForHash()
                .multiGet(CacheConstants.ARTICLE_VIEW_COUNT, dirtyIds);
        Map<Long, Long> snapshot = new LinkedHashMap<>();
        for (int i = 0; i < dirtyIds.size(); i++) {
            String value = values == null ? null : values.get(i);
            if (value != null) {
                snapshot.put(Long.valueOf(dirtyIds.get(i)), Long.valueOf(value));
            }
        }

        if (snapshot.isEmpty()) {
            redisTemplate.opsForSet().remove(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY,
                    dirtyIds.toArray());
            return 0;
        }

        Map<Long, Article> existingArticles = articleMapper.selectBatchIds(snapshot.keySet()).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity()));
        Set<Long> missingIds = snapshot.keySet().stream()
                .filter(id -> !existingArticles.containsKey(id))
                .collect(Collectors.toSet());
        removeArticles(missingIds);

        Map<Long, Long> persistable = snapshot.entrySet().stream()
                .filter(entry -> existingArticles.containsKey(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> right, LinkedHashMap::new));
        if (persistable.isEmpty()) {
            return 0;
        }

        articleMapper.updateViewCounts(persistable);
        persistable.keySet().forEach(articleSearchOutboxService::enqueue);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                persistable.forEach((id, count) -> {
                    try {
                        redisTemplate.execute(REMOVE_DIRTY_IF_UNCHANGED_SCRIPT,
                                List.of(CacheConstants.ARTICLE_VIEW_COUNT, CacheConstants.ARTICLE_VIEW_COUNT_DIRTY),
                                id.toString(), count.toString());
                    } catch (RuntimeException cleanupFailure) {
                        LOGGER.warn("Counter committed but dirty cleanup failed; retained for retry, articleId={}",
                                id, cleanupFailure);
                    }
                });
            }
        });
        return persistable.size();
    }

    @Override
    public void initializeCounters() {
        List<Article> articles = articleMapper.selectArticleCounters();
        redisTemplate.delete(CacheConstants.ARTICLE_VIEW_COUNT_PUBLISHED);
        for (Article article : articles) {
            String articleId = article.getId().toString();
            long databaseCount = article.getViewCount() == null ? 0L : article.getViewCount();
            String legacyValue = redisTemplate.<String, String>opsForHash()
                    .get(CacheConstants.LEGACY_ARTICLE_VIEW_COUNT, articleId);
            long initialCount = Math.max(databaseCount, parseCount(legacyValue, databaseCount));
            Boolean inserted = redisTemplate.<String, String>opsForHash()
                    .putIfAbsent(CacheConstants.ARTICLE_VIEW_COUNT, articleId, Long.toString(initialCount));
            if (Boolean.TRUE.equals(inserted) && initialCount > databaseCount) {
                redisTemplate.opsForSet().add(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, articleId);
            }
            if (Integer.toString(SystemConstants.ARTICLE_STATUS_NORMAL).equals(article.getStatus())) {
                redisTemplate.opsForSet().add(CacheConstants.ARTICLE_VIEW_COUNT_PUBLISHED, articleId);
            }
        }
        LOGGER.info("文章浏览量计数初始化完成，articleCount={}", articles.size());
    }

    @Override
    public void synchronizeArticle(Long articleId) {
        if (articleId == null) {
            return;
        }
        Article article = articleMapper.selectArticleCounterById(articleId);
        if (article == null) {
            removeArticles(List.of(articleId));
            return;
        }
        String id = articleId.toString();
        long databaseCount = article.getViewCount() == null ? 0L : article.getViewCount();
        redisTemplate.<String, String>opsForHash().putIfAbsent(
                CacheConstants.ARTICLE_VIEW_COUNT, id, Long.toString(databaseCount));
        if (Integer.toString(SystemConstants.ARTICLE_STATUS_NORMAL).equals(article.getStatus())) {
            redisTemplate.opsForSet().add(CacheConstants.ARTICLE_VIEW_COUNT_PUBLISHED, id);
        } else {
            redisTemplate.opsForSet().remove(CacheConstants.ARTICLE_VIEW_COUNT_PUBLISHED, id);
        }
    }

    @Override
    public void removeArticles(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }
        Object[] ids = articleIds.stream().filter(Objects::nonNull).map(String::valueOf).toArray();
        if (ids.length == 0) {
            return;
        }
        redisTemplate.opsForHash().delete(CacheConstants.ARTICLE_VIEW_COUNT, ids);
        redisTemplate.opsForSet().remove(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, ids);
        redisTemplate.opsForSet().remove(CacheConstants.ARTICLE_VIEW_COUNT_PUBLISHED, ids);
    }

    @PreDestroy
    public void flushBeforeShutdown() {
        try {
            int flushed = flushDirtyCounters(CacheConstants.ARTICLE_VIEW_FLUSH_BATCH_SIZE * 5);
            LOGGER.info("应用关闭前浏览量刷盘完成，processed={}", flushed);
        } catch (RuntimeException flushFailure) {
            LOGGER.warn("应用关闭前浏览量刷盘失败，dirty 标记保留等待下次补偿", flushFailure);
        }
    }

    private Long executeIncrement(Long articleId, String visitorFingerprint) {
        String id = articleId.toString();
        String dedupKey = CacheConstants.ARTICLE_VIEWED_PREFIX + id + ":" + visitorFingerprint;
        return redisTemplate.execute(INCREMENT_SCRIPT,
                List.of(CacheConstants.ARTICLE_VIEW_COUNT, CacheConstants.ARTICLE_VIEW_COUNT_DIRTY,
                        CacheConstants.ARTICLE_VIEW_COUNT_PUBLISHED, dedupKey),
                id, Long.toString(TimeUnit.MINUTES.toSeconds(CacheConstants.ARTICLE_VIEW_DEDUP_MINUTES)));
    }

    private List<String> scanDirtyIds(int batchSize) {
        List<String> dirtyIds = new ArrayList<>(batchSize);
        ScanOptions options = ScanOptions.scanOptions().count(batchSize).build();
        try (Cursor<String> cursor = redisTemplate.opsForSet()
                .scan(CacheConstants.ARTICLE_VIEW_COUNT_DIRTY, options)) {
            while (cursor.hasNext() && dirtyIds.size() < batchSize) {
                dirtyIds.add(cursor.next());
            }
        }
        return dirtyIds;
    }

    private long parseCount(String value, long fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
