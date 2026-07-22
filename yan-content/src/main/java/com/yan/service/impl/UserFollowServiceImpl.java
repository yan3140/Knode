package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.User;
import com.yan.domain.entity.UserFollow;
import com.yan.domain.vo.FollowUserVo;
import com.yan.domain.vo.PageVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.UserFollowMapper;
import com.yan.mapper.UserMapper;
import com.yan.service.UserFollowService;
import com.yan.service.UserNotificationService;
import com.yan.utils.SecurityUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class UserFollowServiceImpl implements UserFollowService {
    private final UserFollowMapper userFollowMapper;
    private final UserMapper userMapper;
    private final UserNotificationService userNotificationService;

    public UserFollowServiceImpl(UserFollowMapper userFollowMapper, UserMapper userMapper) {
        this(userFollowMapper, userMapper, null);
    }

    @Autowired
    public UserFollowServiceImpl(UserFollowMapper userFollowMapper, UserMapper userMapper,
                                 UserNotificationService userNotificationService) {
        this.userFollowMapper = userFollowMapper;
        this.userMapper = userMapper;
        this.userNotificationService = userNotificationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult follow(Long followeeId) {
        Long followerId = SecurityUtils.getUserId();
        validateFollowTarget(followerId, followeeId);
        if (exists(followerId, followeeId)) {
            return ResponseResult.okResult();
        }
        boolean created = false;
        try {
            created = userFollowMapper.insert(new UserFollow(null, followerId, followeeId, null)) > 0;
        } catch (DuplicateKeyException ignored) {
            // 唯一键保证并发重复关注也是幂等成功。
        }
        if (created && userNotificationService != null) {
            userNotificationService.notify(followeeId, followerId, null, null, null, "FOLLOWED", null);
        }
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult unfollow(Long followeeId) {
        Long followerId = SecurityUtils.getUserId();
        if (followeeId == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        userFollowMapper.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getFolloweeId, followeeId));
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult followingStatus(Long followeeId) {
        if (followeeId == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        Long followerId = SecurityUtils.getUserId();
        return ResponseResult.okResult(Map.of("following", exists(followerId, followeeId)));
    }

    @Override
    public ResponseResult following(Integer pageNum, Integer pageSize) {
        Page<FollowUserVo> page = userFollowMapper.selectFollowing(new Page<>(normalizePage(pageNum), normalizeSize(pageSize)), SecurityUtils.getUserId());
        return ResponseResult.okResult(new PageVo(page.getRecords(), page.getTotal()));
    }

    @Override
    public ResponseResult followers(Integer pageNum, Integer pageSize) {
        Page<FollowUserVo> page = userFollowMapper.selectFollowers(new Page<>(normalizePage(pageNum), normalizeSize(pageSize)), SecurityUtils.getUserId());
        return ResponseResult.okResult(new PageVo(page.getRecords(), page.getTotal()));
    }

    @Override
    public ResponseResult followingOf(Long userId, Integer pageNum, Integer pageSize) {
        if (userId == null || userMapper.selectById(userId) == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        Page<FollowUserVo> page = userFollowMapper.selectFollowing(
                new Page<>(normalizePage(pageNum), normalizeSize(pageSize)), userId);
        return ResponseResult.okResult(new PageVo(page.getRecords(), page.getTotal()));
    }

    private void validateFollowTarget(Long followerId, Long followeeId) {
        if (followeeId == null || followeeId.equals(followerId)) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        User followee = userMapper.selectById(followeeId);
        if (followee == null || !"0".equals(followee.getStatus())) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
    }

    private boolean exists(Long followerId, Long followeeId) {
        return userFollowMapper.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getFolloweeId, followeeId)) > 0;
    }

    private long normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 100);
    }
}
