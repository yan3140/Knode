package com.yan.service;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.User;

public interface LoginService {

    ResponseResult login(User user);

    ResponseResult logout();
}
