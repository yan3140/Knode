package com.yan.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.entity.UserFollow;
import com.yan.domain.vo.FollowUserVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface UserFollowMapper extends BaseMapper<UserFollow> {
 Page<FollowUserVo> selectFollowing(Page<FollowUserVo> page,@Param("userId") Long userId);
 Page<FollowUserVo> selectFollowers(Page<FollowUserVo> page,@Param("userId") Long userId);
 List<Long> selectFollowerIds(@Param("userId") Long userId);
}
