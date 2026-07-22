package com.yan.service;

import com.yan.domain.ResponseResult;

public interface UserProfileService {
    ResponseResult profile(Long userId, Integer pageNum, Integer pageSize);
}
