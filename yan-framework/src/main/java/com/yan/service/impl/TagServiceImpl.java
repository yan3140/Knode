package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.dto.TagDto;
import com.yan.domain.dto.UpdateTagDto;
import com.yan.domain.entity.Tag;
import com.yan.domain.vo.PageVo;
import com.yan.domain.vo.TagVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.TagMapper;
import com.yan.service.TagService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 标签(Tag)表服务实现类
 *
 * @author makejava
 * @since 2025-12-08 18:01:06
 */
@Service("tagService")
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements TagService {

    @Autowired
    private TagMapper tagMapper;
    @Override
    public ResponseResult getTagList(int pageNum, int pageSize, TagDto tagDto) {
        LambdaQueryWrapper<Tag> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.like(StringUtils.hasText(tagDto.getName()),Tag::getName, tagDto.getName())
                    .or()
                    .like(StringUtils.hasText(tagDto.getRemark()),Tag::getRemark, tagDto.getRemark());
        Page<Tag> page = new Page(pageNum, pageSize);
        List<Tag> tags = page(page, queryWrapper).getRecords();
        List<TagVo> tagVos = BeanCopyUtils.copyBeanList(tags, TagVo.class);
        PageVo pageVo = new PageVo(tagVos,page.getTotal());
        return ResponseResult.okResult(pageVo);
    }

    @Override
    public ResponseResult addTag(UpdateTagDto updateTagDto) {
        if(!StringUtils.hasText(updateTagDto.getName())||!StringUtils.hasText(updateTagDto.getRemark())){
            throw new SystemException(AppHttpCodeEnum.CONTENT_NOT_NULL);
        }
        save(toTag(updateTagDto));
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult deleteTag(List<Long> ids) {
        if(ids==null||ids.isEmpty()){
            throw new SystemException(AppHttpCodeEnum.CONTENT_NOT_NULL);
        }
        LambdaUpdateWrapper<Tag> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(Tag::getId, ids);
        updateWrapper.set(Tag::getDelFlag,SystemConstants.TAG_DRAFT_STATUS);

        tagMapper.update(null,updateWrapper);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult updateTag(UpdateTagDto updateTagDto) {
        LambdaUpdateWrapper<Tag> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Tag::getId, updateTagDto.getId());
        updateWrapper.set(StringUtils.hasText(updateTagDto.getName()),Tag::getName,updateTagDto.getName())
                .set(StringUtils.hasText(updateTagDto.getRemark()),Tag::getRemark,updateTagDto.getRemark());
        tagMapper.update(null,updateWrapper);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult getTagById(Long id) {
        LambdaQueryWrapper<Tag> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Tag::getId, id);
        Tag tag = tagMapper.selectOne(queryWrapper);
        return ResponseResult.okResult(tag);
    }

    @Override
    public ResponseResult listAllTag() {
        LambdaQueryWrapper<Tag> queryWrapper = new LambdaQueryWrapper<>();
        List<TagVo> tagVos = BeanCopyUtils.copyBeanList(list(queryWrapper), TagVo.class);
        return ResponseResult.okResult(tagVos);
    }

    public static Tag toTag(UpdateTagDto updateTagDto) {
        Tag tag = BeanCopyUtils.copyBean(updateTagDto, Tag.class);
        tag.setDelFlag(SystemConstants.TAG_NORMAL_STATUS);
        return tag;
    }
}

