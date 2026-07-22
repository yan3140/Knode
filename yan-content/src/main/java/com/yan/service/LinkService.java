package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Link;

public interface LinkService extends IService<Link> {
    ResponseResult getAllLink();

    ResponseResult listLinks(Integer pageNum, Integer pageSize, String name, String status);

    ResponseResult getLink(Long id);

    ResponseResult addLink(Link link);

    ResponseResult updateLink(Link link);

    ResponseResult deleteLinks(java.util.List<Long> ids);

    ResponseResult updateLinkStatus(Long id, String status);
}
