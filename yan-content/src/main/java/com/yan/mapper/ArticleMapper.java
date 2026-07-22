package com.yan.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.domain.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;
@Mapper public interface ArticleMapper extends BaseMapper<Article> {
 List<Article> selectArticleCounters(); Article selectArticleCounterById(@Param("id") Long id);
 int updateViewCounts(@Param("viewCounts") Map<Long, Long> viewCounts);
}
