package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Comment;
import com.yan.domain.entity.User;
import com.yan.domain.vo.CommentVo;
import com.yan.domain.vo.PageVo;
import com.yan.mapper.CommentMapper;
import com.yan.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentServiceImplTest {

    private CommentMapper commentMapper;
    private UserMapper userMapper;
    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        userMapper = mock(UserMapper.class);
        commentService = new CommentServiceImpl();
        ReflectionTestUtils.setField(commentService, "baseMapper", commentMapper);
        ReflectionTestUtils.setField(commentService, "userMapper", userMapper);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void commentListShouldBatchChildrenAndUsersAndKeepNestedReplies() {
        Comment rootOne = comment(1L, -1L, -1L, -1L, 1L);
        Comment rootTwo = comment(2L, -1L, -1L, -1L, 2L);
        Comment directReply = comment(10L, 1L, 1L, 1L, 2L);
        Comment nestedReply = comment(11L, 1L, 10L, 4L, 3L);

        when(commentMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Comment> page = invocation.getArgument(0);
            page.setRecords(Arrays.asList(rootOne, rootTwo));
            page.setTotal(2L);
            return page;
        });
        when(commentMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(directReply, nestedReply));
        when(userMapper.selectBatchIds(any(Collection.class)))
                .thenReturn(Arrays.asList(user(1L, "root-user"), user(2L, "reply-user"),
                        user(3L, "nested-user"), user(4L, "target-only-user")));

        ResponseResult result = commentService.commentList("0", 1L, 1, 10);

        PageVo pageVo = (PageVo) result.getData();
        List<CommentVo> roots = pageVo.getRows();
        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).getChildren()).extracting(CommentVo::getId)
                .containsExactly(10L, 11L);
        assertThat(roots.get(1).getChildren()).isEmpty();
        assertThat(roots.get(0).getChildren().get(1).getUsername()).isEqualTo("nested-user");
        assertThat(roots.get(0).getChildren().get(1).getToCommentUserName()).isEqualTo("target-only-user");
        verify(commentMapper, times(1)).selectList(any(Wrapper.class));
        verify(userMapper, times(1)).selectBatchIds(any(Collection.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void commentListShouldNotQueryChildrenOrUsersWhenPageIsEmpty() {
        when(commentMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Comment> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0L);
            return page;
        });

        ResponseResult result = commentService.commentList("0", 1L, null, null);

        PageVo pageVo = (PageVo) result.getData();
        assertThat(pageVo.getRows()).isEmpty();
        verify(commentMapper, times(0)).selectList(any(Wrapper.class));
        verify(userMapper, times(0)).selectBatchIds(any(Collection.class));
    }

    private Comment comment(Long id, Long rootId, Long toCommentId, Long toCommentUserId, Long createBy) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setType("0");
        comment.setArticleId(1L);
        comment.setRootId(rootId);
        comment.setToCommentId(toCommentId);
        comment.setToCommentUserId(toCommentUserId);
        comment.setCreateBy(createBy);
        return comment;
    }

    private User user(Long id, String nickName) {
        User user = new User();
        user.setId(id);
        user.setNickName(nickName);
        return user;
    }
}
