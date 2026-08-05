package com.yan.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.LoginUser;
import com.yan.domain.entity.User;
import com.yan.domain.vo.PageVo;
import com.yan.mapper.UserNotificationMapper;
import com.yan.notification.NotificationDeliveryOutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserNotificationServiceImplTest {
    private UserNotificationMapper mapper;
    private UserNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserNotificationMapper.class);
        service = new UserNotificationServiceImpl(mapper);
        User user = new User();
        user.setId(10L);
        user.setUserName("tester");
        user.setPassword("unused");
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void inboxShouldAlwaysQueryCurrentUsersMailbox() {
        Page page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(mapper.selectInbox(any(Page.class), eq(10L))).thenReturn(page);

        ResponseResult result = service.inbox(1, 10);

        assertThat(((PageVo) result.getData()).getTotal()).isZero();
        verify(mapper).selectInbox(any(Page.class), eq(10L));
    }

    @Test
    void markReadShouldScopeUpdateToCurrentUser() {
        service.markRead(99L);

        verify(mapper).markRead(99L, 10L);
    }

    @Test
    void unreadCountShouldReturnOnlyCurrentUsersCount() {
        when(mapper.countUnread(10L)).thenReturn(3L);

        ResponseResult result = service.unreadCount();

        assertThat((Map<String, Long>) result.getData()).containsEntry("unreadCount", 3L);
        verify(mapper).countUnread(10L);
    }

    @Test
    void notifyShouldUseReliableDeliveryOutboxWhenAvailable() {
        NotificationDeliveryOutboxService deliveryOutboxService = mock(NotificationDeliveryOutboxService.class);
        service = new UserNotificationServiceImpl(mapper, deliveryOutboxService);

        service.notify(20L, 10L, 100L, 200L, -1L, "COMMENT", "hello");

        verify(deliveryOutboxService).enqueue(20L, 10L, 100L, 200L, -1L, "COMMENT", "hello");
    }
}
