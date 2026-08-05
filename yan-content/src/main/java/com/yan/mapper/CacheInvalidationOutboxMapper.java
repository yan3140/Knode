package com.yan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.domain.entity.CacheInvalidationOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CacheInvalidationOutboxMapper extends BaseMapper<CacheInvalidationOutboxEvent> {
    int enqueue(@Param("taskKey") String taskKey,
                @Param("cacheKey") String cacheKey,
                @Param("patternFlag") int patternFlag,
                @Param("reason") String reason,
                @Param("maxRetry") int maxRetry);

    List<CacheInvalidationOutboxEvent> selectPending(@Param("limit") int limit);

    int claim(@Param("id") Long id, @Param("leaseSeconds") int leaseSeconds);

    int markProcessingStarted(@Param("id") Long id);

    int markSucceeded(@Param("id") Long id);

    int markForRetry(@Param("id") Long id, @Param("delaySeconds") int delaySeconds, @Param("reason") String reason);

    int recoverExpiredClaims();

    int requeuePending();

    Long sumProcessCount();

    Integer maxProcessCount();
}
