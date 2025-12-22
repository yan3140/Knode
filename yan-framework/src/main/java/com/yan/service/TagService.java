package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.ResponseResult;
import com.yan.domain.dto.TagDto;
import com.yan.domain.dto.UpdateTagDto;
import com.yan.domain.entity.Tag;

import java.util.List;

/**
 * 标签(Tag)表服务接口
 *
 * @author makejava
 * @since 2025-12-08 18:01:06
 */
public interface TagService extends IService<Tag> {
    ResponseResult getTagList(int pageNum, int pageSize, TagDto tagDto);

    ResponseResult addTag(UpdateTagDto updateTagDto);

    ResponseResult deleteTag(List<Long> ids);

    ResponseResult updateTag(UpdateTagDto updateTagDto);

    ResponseResult getTagById(Long id);

    ResponseResult listAllTag();
}
