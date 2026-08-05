package com.yan.cache;

public interface CacheInvalidationRecorder {
    void record(String cacheKey, boolean pattern, String reason);
}
