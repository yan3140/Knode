package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Comment;

/**
 * 评论表(Comment)表服务接口
 *
 * @author makejava
 * @since 2025-11-28 17:02:29
 */
public interface CommentService extends IService<Comment> {
    ResponseResult commentList(String commentType, Long articleId, Integer pageNum, Integer pageSize);

    ResponseResult addComment(Comment comment);
}
