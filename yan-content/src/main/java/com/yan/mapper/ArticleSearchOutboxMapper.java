package com.yan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.domain.entity.ArticleSearchOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ArticleSearchOutboxMapper extends BaseMapper<ArticleSearchOutboxEvent> {

    List<ArticleSearchOutboxEvent> selectPending(@Param("limit") int limit);

    int claim(@Param("id") Long id, @Param("leaseSeconds") int leaseSeconds);

    int markSucceeded(@Param("id") Long id);

    int markForRetry(@Param("id") Long id, @Param("delaySeconds") int delaySeconds,
                     @Param("lastError") String lastError);

    int recoverExpiredClaims();

    int requeuePending();
}
