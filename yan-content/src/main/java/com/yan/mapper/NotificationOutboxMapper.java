package com.yan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.domain.entity.NotificationOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationOutboxMapper extends BaseMapper<NotificationOutboxEvent> {
    int enqueue(@Param("articleId") Long articleId, @Param("authorId") Long authorId,
                @Param("taskKey") String taskKey, @Param("maxRetry") int maxRetry);

    List<NotificationOutboxEvent> selectPending(@Param("limit") int limit);

    int claim(@Param("id") Long id, @Param("leaseSeconds") int leaseSeconds);

    int continueFromCursor(@Param("id") Long id, @Param("cursorFollowerId") Long cursorFollowerId);

    int markSucceeded(@Param("id") Long id);

    int markForRetry(@Param("id") Long id, @Param("delaySeconds") int delaySeconds, @Param("reason") String reason);

    int markProcessingStarted(@Param("id") Long id);

    int recoverExpiredClaims();

    int requeuePending();

    Long sumProcessCount();

    Integer maxProcessCount();

    List<Long> selectFollowerIdsAfter(@Param("authorId") Long authorId, @Param("cursorFollowerId") Long cursorFollowerId, @Param("limit") int limit);

    long countFollowers(@Param("authorId") Long authorId);

    int insertAuthorPublishEvent(@Param("authorId") Long authorId, @Param("articleId") Long articleId);
}
