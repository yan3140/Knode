package com.yan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.domain.entity.NotificationDeliveryOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationDeliveryOutboxMapper extends BaseMapper<NotificationDeliveryOutboxEvent> {
    int enqueue(@Param("taskKey") String taskKey,
                @Param("receiverId") Long receiverId,
                @Param("actorId") Long actorId,
                @Param("articleId") Long articleId,
                @Param("commentId") Long commentId,
                @Param("rootCommentId") Long rootCommentId,
                @Param("type") String type,
                @Param("content") String content,
                @Param("maxRetry") int maxRetry);

    List<NotificationDeliveryOutboxEvent> selectPending(@Param("limit") int limit);

    int claim(@Param("id") Long id, @Param("leaseSeconds") int leaseSeconds);

    int markProcessingStarted(@Param("id") Long id);

    int markSucceeded(@Param("id") Long id);

    int markForRetry(@Param("id") Long id, @Param("delaySeconds") int delaySeconds, @Param("reason") String reason);

    int recoverExpiredClaims();

    int requeuePending();
}
