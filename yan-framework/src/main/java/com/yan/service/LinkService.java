package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Link;

public interface LinkService extends IService<Link> {
    ResponseResult getAllLink();
}
