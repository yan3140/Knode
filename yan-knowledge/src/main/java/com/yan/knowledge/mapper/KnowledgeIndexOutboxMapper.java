package com.yan.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.knowledge.domain.entity.KnowledgeIndexOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface KnowledgeIndexOutboxMapper extends BaseMapper<KnowledgeIndexOutboxEvent> {

    int enqueue(@Param("taskKey") String taskKey,
                @Param("documentId") Long documentId,
                @Param("documentVersionId") Long documentVersionId,
                @Param("operation") String operation,
                @Param("maxRetry") int maxRetry);

    List<KnowledgeIndexOutboxEvent> selectPending(@Param("limit") int limit);

    int claim(@Param("id") Long id, @Param("leaseSeconds") int leaseSeconds);

    int markSucceeded(@Param("id") Long id);

    int markProcessingStarted(@Param("id") Long id);

    int markForRetry(@Param("id") Long id, @Param("delaySeconds") int delaySeconds,
                     @Param("lastError") String lastError);

    int recoverExpiredClaims();
}
