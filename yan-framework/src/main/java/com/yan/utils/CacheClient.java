package com.yan.utils;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
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

    public CacheClient(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    @SuppressWarnings("unchecked")
    public <T> T queryWithMutex(String key, int ttlMinutes, int jitterMinutes, Supplier<T> databaseFallback) {
        Object cached = readCacheOrFallback(key, databaseFallback);
        if (cached != CacheMiss.INSTANCE) {
            return NULL_VALUE.equals(cached) ? null : (T) cached;
        }

        String lockKey = LOCK_PREFIX + key;
        boolean locked = false;
        try {
            for (int attempt = 0; attempt < LOCK_RETRY_COUNT; attempt++) {
                try {
                    locked = redisCache.setCacheObjectIfAbsent(
                            lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
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
                    redisCache.deleteObject(lockKey);
                } catch (RuntimeException ignored) {
                    // The lock has a short TTL and will release itself when Redis recovers.
                }
            }
        }
    }

    public void delete(String key) {
        try {
            redisCache.deleteObject(key);
        } catch (RuntimeException ignored) {
            // Cache invalidation must not roll back a successful database write.
        }
    }

    public void deleteAfterCommit(String... keys) {
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

    private enum CacheMiss {
        INSTANCE
    }
}
