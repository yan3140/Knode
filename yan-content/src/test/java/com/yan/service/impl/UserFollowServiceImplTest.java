package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yan.domain.entity.LoginUser;
import com.yan.domain.entity.User;
import com.yan.domain.entity.UserFollow;
import com.yan.mapper.UserFollowMapper;
import com.yan.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserFollowServiceImplTest {
    private UserFollowMapper userFollowMapper;
    private UserMapper userMapper;
    private UserFollowServiceImpl service;

    @BeforeEach
    void setUp() {
        userFollowMapper = mock(UserFollowMapper.class);
        userMapper = mock(UserMapper.class);
        service = new UserFollowServiceImpl(userFollowMapper, userMapper);
        loginAs(10L);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void followShouldBeIdempotentWhenRelationAlreadyExists() {
        when(userMapper.selectById(20L)).thenReturn(activeUser(20L));
        when(userFollowMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        service.follow(20L);

        verify(userFollowMapper, never()).insert(any(UserFollow.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void followShouldTreatConcurrentUniqueKeyConflictAsSuccess() {
        when(userMapper.selectById(20L)).thenReturn(activeUser(20L));
        when(userFollowMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(userFollowMapper.insert(any(UserFollow.class))).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatCode(() -> service.follow(20L)).doesNotThrowAnyException();
        verify(userFollowMapper).insert(any(UserFollow.class));
    }

    @Test
    void followShouldRejectSelfFollowBeforeDatabaseWrite() {
        assertThatThrownBy(() -> service.follow(10L)).hasMessage("参数不合法");

        verify(userMapper, never()).selectById(any());
        verify(userFollowMapper, never()).insert(any(UserFollow.class));
    }

    private User activeUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setStatus("0");
        return user;
    }

    private void loginAs(Long id) {
        User user = activeUser(id);
        user.setUserName("tester");
        user.setPassword("unused");
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }
}
