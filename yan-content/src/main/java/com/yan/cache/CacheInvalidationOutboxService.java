package com.yan.cache;

import com.yan.domain.entity.CacheInvalidationOutboxEvent;
import com.yan.mapper.CacheInvalidationOutboxMapper;
import com.yan.reliabletask.ReliableTaskPolicy;
import com.yan.reliabletask.ReliableTaskStatus;
import com.yan.utils.RedisCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
public class CacheInvalidationOutboxService implements CacheInvalidationRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationOutboxService.class);

    private final CacheInvalidationOutboxMapper outboxMapper;
    private final RedisCache redisCache;
    private final int pollBatchSize;
    private final int leaseSeconds;
    private final ReliableTaskPolicy taskPolicy;

    public CacheInvalidationOutboxService(CacheInvalidationOutboxMapper outboxMapper,
                                          RedisCache redisCache,
                                          @Value("${blog.cache.invalidation.poll-batch-size:50}") int pollBatchSize,
                                          @Value("${blog.cache.invalidation.lease-seconds:30}") int leaseSeconds,
                                          @Value("${blog.cache.invalidation.retry-delay-seconds:10}") int retryDelaySeconds,
                                          @Value("${blog.cache.invalidation.max-retry-delay-seconds:300}") int maxRetryDelaySeconds,
                                          @Value("${blog.cache.invalidation.max-retry:8}") int maxRetry) {
        this.outboxMapper = outboxMapper;
        this.redisCache = redisCache;
        this.pollBatchSize = positive(pollBatchSize, "poll-batch-size");
        this.leaseSeconds = positive(leaseSeconds, "lease-seconds");
        this.taskPolicy = new ReliableTaskPolicy(maxRetry, retryDelaySeconds, maxRetryDelaySeconds);
    }

    @Override
    public void record(String cacheKey, boolean pattern, String reason) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return;
        }
        String taskKey = (pattern ? "CACHE_INVALIDATE_PATTERN:" : "CACHE_INVALIDATE_KEY:") + cacheKey;
        outboxMapper.enqueue(taskKey, cacheKey, pattern ? 1 : 0, taskPolicy.safeError(reason), taskPolicy.maxRetry());
    }

    @Scheduled(fixedDelayString = "${blog.cache.invalidation.poll-delay-ms:1000}")
    public void retryPending() {
        outboxMapper.recoverExpiredClaims();
        for (CacheInvalidationOutboxEvent event : outboxMapper.selectPending(pollBatchSize)) {
            if (outboxMapper.claim(event.getId(), leaseSeconds) == 1) {
                outboxMapper.markProcessingStarted(event.getId());
                invalidate(event);
            }
        }
    }

    private void invalidate(CacheInvalidationOutboxEvent event) {
        try {
            if (Integer.valueOf(1).equals(event.getPatternFlag())) {
                Collection<String> keys = redisCache.keys(event.getCacheKey());
                if (keys != null && !keys.isEmpty()) {
                    redisCache.deleteObject(keys);
                }
            } else {
                redisCache.deleteObject(event.getCacheKey());
            }
            outboxMapper.markSucceeded(event.getId());
        } catch (RuntimeException deleteFailure) {
            int delaySeconds = taskPolicy.nextRetryDelaySeconds(event.getRetryCount());
            outboxMapper.markForRetry(event.getId(), delaySeconds, taskPolicy.safeError(deleteFailure.getMessage()));
            String nextStatus = taskPolicy.willDeadLetter(event.getRetryCount())
                    ? ReliableTaskStatus.FAILED : ReliableTaskStatus.PENDING;
            LOGGER.warn("event=cache_invalidation_retry_scheduled outboxId={} cacheKey={} nextStatus={} retryDelaySeconds={}",
                    event.getId(), event.getCacheKey(), nextStatus, delaySeconds, deleteFailure);
        }
    }

    private int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("cache invalidation " + name + " must be positive");
        }
        return value;
    }
}
