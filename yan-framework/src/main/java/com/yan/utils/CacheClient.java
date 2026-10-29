package com.yan.utils;

import com.yan.cache.CacheInvalidationRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class CacheClient {
    private static final String NULL_VALUE = "__CACHE_NULL__";
    private static final String LOCK_PREFIX = "lock:";
    private static final int NULL_TTL_MINUTES = 2;
    private static final int LOCK_TTL_SECONDS = 10;
    private static final int LOCK_RETRY_COUNT = 20;
    private static final long LOCK_RETRY_INTERVAL_MILLIS = 25L;

    private final RedisCache redisCache;
    private final CacheInvalidationRecorder invalidationRecorder;

    public CacheClient(RedisCache redisCache) {
        this(redisCache, null);
    }

    @Autowired
    public CacheClient(RedisCache redisCache, ObjectProvider<CacheInvalidationRecorder> invalidationRecorderProvider) {
        this.redisCache = redisCache;
        this.invalidationRecorder = invalidationRecorderProvider == null ? null : invalidationRecorderProvider.getIfAvailable();
    }

    @SuppressWarnings("unchecked")
    public <T> T queryWithMutex(String key, int ttlMinutes, int jitterMinutes, Supplier<T> databaseFallback) {
        Object cached = readCacheOrFallback(key, databaseFallback);
        if (cached != CacheMiss.INSTANCE) {
            return NULL_VALUE.equals(cached) ? null : (T) cached;
        }

        String lockKey = LOCK_PREFIX + key;
        String lockToken = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            for (int attempt = 0; attempt < LOCK_RETRY_COUNT; attempt++) {
                try {
                    locked = redisCache.setCacheObjectIfAbsent(
                            lockKey, lockToken, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                } catch (RuntimeException redisFailure) {
                    return databaseFallback.get();
                }
                if (locked) {
                    break;
                }
                Object retryValue = readCacheOrFallback(key, databaseFallback);
                if (retryValue != CacheMiss.INSTANCE) {
                    return NULL_VALUE.equals(retryValue) ? null : (T) retryValue;
                }
                Thread.sleep(LOCK_RETRY_INTERVAL_MILLIS);
            }

            if (!locked) {
                return databaseFallback.get();
            }

            Object secondCheck = readCacheOrFallback(key, databaseFallback);
            if (secondCheck != CacheMiss.INSTANCE) {
                return NULL_VALUE.equals(secondCheck) ? null : (T) secondCheck;
            }

            T value = databaseFallback.get();
            writeCache(key, value, ttlMinutes, jitterMinutes);
            return value;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return databaseFallback.get();
        } finally {
            if (locked) {
                try {
                    redisCache.deleteObjectIfValueMatches(lockKey, lockToken);
                } catch (RuntimeException ignored) {
                    // The lock has a short TTL and will release itself when Redis recovers.
                }
            }
        }
    }

    public void delete(String key) {
        try {
            redisCache.deleteObject(key);
        } catch (RuntimeException deleteFailure) {
            // Cache invalidation must not roll back a successful database write.
            recordInvalidation(key, false, deleteFailure);
        }
    }

    public void deleteAfterCommit(String... keys) {
        recordBeforeCommit(keys, false);
        Runnable eviction = () -> Arrays.stream(keys).forEach(this::delete);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eviction.run();
                }
            });
            return;
        }
        eviction.run();
    }

    public void deletePattern(String pattern) {
        try {
            java.util.Collection<String> keys = redisCache.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisCache.deleteObject(keys);
            }
        } catch (RuntimeException deleteFailure) {
            recordInvalidation(pattern, true, deleteFailure);
        }
    }

    public void deletePatternAfterCommit(String... patterns) {
        recordBeforeCommit(patterns, true);
        Runnable eviction = () -> Arrays.stream(patterns).forEach(this::deletePattern);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eviction.run();
                }
            });
            return;
        }
        eviction.run();
    }

    private Object readCacheOrFallback(String key, Supplier<?> databaseFallback) {
        try {
            Object value = redisCache.getCacheObject(key);
            return value == null ? CacheMiss.INSTANCE : value;
        } catch (RuntimeException redisFailure) {
            return databaseFallback.get();
        }
    }

    private <T> void writeCache(String key, T value, int ttlMinutes, int jitterMinutes) {
        try {
            if (value == null) {
                redisCache.setCacheObject(key, NULL_VALUE, NULL_TTL_MINUTES, TimeUnit.MINUTES);
                return;
            }
            int jitter = jitterMinutes <= 0 ? 0 : ThreadLocalRandom.current().nextInt(jitterMinutes + 1);
            redisCache.setCacheObject(key, value, ttlMinutes + jitter, TimeUnit.MINUTES);
        } catch (RuntimeException ignored) {
            // Redis failure degrades to the database result already obtained above.
        }
    }

    private void recordInvalidation(String key, boolean pattern, RuntimeException failure) {
        if (invalidationRecorder == null) {
            return;
        }
        try {
            invalidationRecorder.recordFailure(key, pattern,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        } catch (RuntimeException ignored) {
            // The database write has already succeeded; invalidation recording must not break callers.
        }
    }

    private void recordBeforeCommit(String[] keys, boolean pattern) {
        if (invalidationRecorder != null) {
            for (String key : keys) {
                invalidationRecorder.record(key, pattern, "Cache invalidation requested");
            }
        }
    }

    private enum CacheMiss {
        INSTANCE
    }
}
