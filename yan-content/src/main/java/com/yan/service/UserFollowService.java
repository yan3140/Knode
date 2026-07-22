package com.yan.service;

import com.yan.domain.ResponseResult;

public interface UserFollowService {
    ResponseResult follow(Long followeeId);

    ResponseResult unfollow(Long followeeId);

    ResponseResult followingStatus(Long followeeId);

    ResponseResult following(Integer pageNum, Integer pageSize);

    ResponseResult followers(Integer pageNum, Integer pageSize);

    ResponseResult followingOf(Long userId, Integer pageNum, Integer pageSize);
}
