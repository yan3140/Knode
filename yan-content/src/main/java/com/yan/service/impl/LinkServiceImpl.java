package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Link;
import com.yan.domain.vo.LinkVo;
import com.yan.domain.vo.PageVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.mapper.LinkMapper;
import com.yan.service.LinkService;
import com.yan.utils.BeanCopyUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class LinkServiceImpl extends ServiceImpl<LinkMapper, Link> implements LinkService {
    @Override
    public ResponseResult getAllLink() {
        LambdaQueryWrapper<Link> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Link::getStatus, SystemConstants.LINK_APPROVED);
        List<Link> links = list(queryWrapper);
        List<LinkVo> linkVos = BeanCopyUtils.copyBeanList(links, LinkVo.class);
        return ResponseResult.okResult(linkVos);
    }

    @Override
    public ResponseResult listLinks(Integer pageNum, Integer pageSize, String name, String status) {
        Page<Link> page = page(new Page<>(normalizePage(pageNum), normalizeSize(pageSize)), new LambdaQueryWrapper<Link>()
                .like(StringUtils.hasText(name), Link::getName, name)
                .eq(StringUtils.hasText(status), Link::getStatus, status)
                .orderByDesc(Link::getCreateTime));
        return ResponseResult.okResult(new PageVo(page.getRecords(), page.getTotal()));
    }

    @Override
    public ResponseResult getLink(Long id) {
        Link link = getById(id);
        return link == null ? ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR) : ResponseResult.okResult(link);
    }

    @Override
    public ResponseResult addLink(Link link) {
        if (link == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        if (!StringUtils.hasText(link.getStatus())) {
            link.setStatus(SystemConstants.LINK_PENDING);
        }
        save(link);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult updateLink(Link link) {
        if (link == null || link.getId() == null || getById(link.getId()) == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        updateById(link);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult deleteLinks(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        removeByIds(ids);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult updateLinkStatus(Long id, String status) {
        if (id == null || !StringUtils.hasText(status) || getById(id) == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        Link link = new Link();
        link.setId(id);
        link.setStatus(status);
        updateById(link);
        return ResponseResult.okResult();
    }

    private long normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 100);
    }
}
