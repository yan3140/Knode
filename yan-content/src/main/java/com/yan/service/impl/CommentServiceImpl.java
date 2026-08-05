package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.constants.CacheConstants;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Comment;
import com.yan.domain.entity.Article;
import com.yan.domain.entity.User;
import com.yan.domain.vo.CommentVo;
import com.yan.domain.vo.PageVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.CommentMapper;
import com.yan.mapper.ArticleMapper;
import com.yan.mapper.UserMapper;
import com.yan.service.CommentService;
import com.yan.service.UserNotificationService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private UserNotificationService userNotificationService;
    @Autowired
    private CacheClient cacheClient;

    @Override
    public ResponseResult commentList(String commentType, Long articleId, Integer pageNum, Integer pageSize) {
        long safePageNum = normalizePageNum(pageNum);
        long safePageSize = normalizePageSize(pageSize);
        if (isArticleFirstPage(commentType, articleId, safePageNum, safePageSize)) {
            return cacheClient.queryWithMutex(
                    commentFirstPageKey(articleId),
                    CacheConstants.ARTICLE_COMMENT_TTL_MINUTES,
                    CacheConstants.TTL_JITTER_MINUTES,
                    () -> loadCommentList(commentType, articleId, safePageNum, safePageSize)
            );
        }
        return loadCommentList(commentType, articleId, safePageNum, safePageSize);
    }

    private ResponseResult loadCommentList(String commentType, Long articleId, long pageNum, long pageSize) {
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SystemConstants.ARTICLE_COMMENT.equals(commentType), Comment::getArticleId, articleId);
        queryWrapper.eq(Comment::getRootId, -1);
        queryWrapper.eq(Comment::getType, commentType);
        queryWrapper.orderByAsc(Comment::getCreateTime);

        Page<Comment> page = new Page<>(pageNum, pageSize);
        page(page, queryWrapper);
        List<Comment> rootComments = page.getRecords();
        if (rootComments.isEmpty()) {
            return ResponseResult.okResult(new PageVo(Collections.emptyList(), page.getTotal()));
        }

        Set<Long> rootIds = rootComments.stream()
                .map(Comment::getId)
                .collect(Collectors.toSet());
        List<Comment> childComments = list(new LambdaQueryWrapper<Comment>()
                .in(Comment::getRootId, rootIds)
                .orderByAsc(Comment::getRootId)
                .orderByAsc(Comment::getCreateTime));

        List<Comment> allComments = new ArrayList<>(rootComments.size() + childComments.size());
        allComments.addAll(rootComments);
        allComments.addAll(childComments);
        Map<Long, String> userNames = loadUserNames(allComments);
        List<CommentVo> commentVos = assembleCommentTree(rootComments, childComments, userNames);
        return ResponseResult.okResult(new PageVo(commentVos, page.getTotal()));
    }

    @Override
    public ResponseResult addComment(Comment comment) {
        if (!StringUtils.hasText(comment.getContent())) {
            throw new SystemException(AppHttpCodeEnum.CONTENT_NOT_NULL);
        }
        save(comment);
        evictCommentCache(comment);
        createCommentNotification(comment);
        return ResponseResult.okResult();
    }

    private void evictCommentCache(Comment comment) {
        if (SystemConstants.ARTICLE_COMMENT.equals(comment.getType()) && comment.getArticleId() != null
                && cacheClient != null) {
            cacheClient.deleteAfterCommit(commentFirstPageKey(comment.getArticleId()));
        }
    }

    private void createCommentNotification(Comment comment) {
        if (!SystemConstants.ARTICLE_COMMENT.equals(comment.getType()) || comment.getArticleId() == null
                || articleMapper == null || userNotificationService == null) {
            return;
        }
        Article article = articleMapper.selectById(comment.getArticleId());
        if (article == null) {
            return;
        }
        Long receiverId = comment.getToCommentUserId() != null && comment.getToCommentUserId() > 0
                ? comment.getToCommentUserId() : article.getCreateBy();
        String type = comment.getToCommentUserId() != null && comment.getToCommentUserId() > 0
                ? "COMMENT_REPLY" : "COMMENT";
        String content = comment.getContent().length() > 500 ? comment.getContent().substring(0, 500) : comment.getContent();
        userNotificationService.notify(receiverId, comment.getCreateBy(), comment.getArticleId(), comment.getId(),
                comment.getRootId(), type, content);
    }

    List<CommentVo> assembleCommentTree(List<Comment> rootComments, List<Comment> childComments,
                                        Map<Long, String> userNames) {
        List<CommentVo> rootCommentVos = toCommentVoList(rootComments, userNames);
        Map<Long, List<CommentVo>> childrenByRootId = toCommentVoList(childComments, userNames).stream()
                .collect(Collectors.groupingBy(CommentVo::getRootId, LinkedHashMap::new, Collectors.toList()));
        for (CommentVo rootCommentVo : rootCommentVos) {
            rootCommentVo.setChildren(childrenByRootId.getOrDefault(rootCommentVo.getId(), Collections.emptyList()));
        }
        return rootCommentVos;
    }

    private Map<Long, String> loadUserNames(List<Comment> comments) {
        Set<Long> userIds = comments.stream()
                .flatMap(comment -> java.util.stream.Stream.of(comment.getCreateBy(), comment.getToCommentUserId()))
                .filter(userId -> userId != null && userId != -1L)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickName));
    }

    private List<CommentVo> toCommentVoList(List<Comment> commentList, Map<Long, String> userNames) {
        List<CommentVo> commentVoList = BeanCopyUtils.copyBeanList(commentList, CommentVo.class);
        for (CommentVo commentVo : commentVoList) {
            commentVo.setUsername(userNames.get(commentVo.getCreateBy()));
            if (commentVo.getToCommentUserId() != null && commentVo.getToCommentUserId() != -1L) {
                commentVo.setToCommentUserName(userNames.get(commentVo.getToCommentUserId()));
            }
        }
        return commentVoList;
    }

    private long normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long normalizePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 100);
    }

    private boolean isArticleFirstPage(String commentType, Long articleId, long pageNum, long pageSize) {
        return SystemConstants.ARTICLE_COMMENT.equals(commentType)
                && articleId != null
                && pageNum == 1L
                && pageSize == 10L;
    }

    private String commentFirstPageKey(Long articleId) {
        return CacheConstants.ARTICLE_COMMENT_FIRST_PAGE_PREFIX + articleId;
    }
}

