package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Comment;
import com.yan.domain.entity.User;
import com.yan.domain.vo.CommentVo;
import com.yan.domain.vo.PageVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.CommentMapper;
import com.yan.mapper.UserMapper;
import com.yan.service.CommentService;
import com.yan.utils.BeanCopyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评论表(Comment)表服务实现类
 *
 * @author makejava
 * @since 2025-11-28 17:02:29
 */
@Service("commentService")
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public ResponseResult commentList(String commentType, Long articleId, Integer pageNum, Integer pageSize) {
        //查询对应文章的根评论

        //对articleId进行判断
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<Comment>();
        queryWrapper.eq(SystemConstants.ARTICLE_COMMENT.equals(commentType), Comment::getArticleId, articleId);
        //根评论rootId==-1
        queryWrapper.eq(Comment::getRootId, -1);
        //评论类型判断
        queryWrapper.eq(Comment::getType, commentType);
        //分页查询
        Page<Comment> page = new Page(pageNum, pageSize);
        page(page, queryWrapper);
        List<CommentVo> commentVos = toCommentVoList(page.getRecords());
        //查询所有跟评论对应的子评论集合，赋值给对应属性
        for (CommentVo commentVo : commentVos) {
            List<CommentVo> children = getChildren(commentVo.getId());
            commentVo.setChildren(children);
        }
        return ResponseResult.okResult(new PageVo(commentVos, page.getTotal()));
    }

    @Override
    public ResponseResult addComment(Comment comment) {
        if (!StringUtils.hasText(comment.getContent())) {
            throw new SystemException(AppHttpCodeEnum.CONTENT_NOT_NULL);
        }
        save(comment);
        return ResponseResult.okResult();
    }

    private List<CommentVo> toCommentVoList(List<Comment> commentList) {
        List<CommentVo> commentVoList = BeanCopyUtils.copyBeanList(commentList, CommentVo.class);
        Set<Long> userIds = commentVoList.stream()
                .map(CommentVo::getCreateBy)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return commentVoList;
        }

        List<User> users = userMapper.selectByIds(userIds);
        Map<Long, String> map = users.stream()
                .collect(Collectors.toMap(User::getId, User::getNickName));
        for (CommentVo commentVo : commentVoList) {
            commentVo.setUsername(map.get(commentVo.getCreateBy()));
            if (commentVo.getToCommentUserId() != -1) {
                commentVo.setToCommentUserName(map.get(commentVo.getToCommentUserId()));
            }
        }
        return commentVoList;
    }

    private List<CommentVo> getChildren(Long id) {
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Comment::getToCommentId, id);
        queryWrapper.orderByAsc(Comment::getCreateTime);
        List<Comment> comments = list(queryWrapper);

        List<CommentVo> children = toCommentVoList(comments);
        return children;
    }
}

