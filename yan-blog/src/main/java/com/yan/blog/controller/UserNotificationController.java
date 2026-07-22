package com.yan.blog.controller;

import com.yan.domain.ResponseResult;
import com.yan.service.UserNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/notification", "/api/blog/notification"})
public class UserNotificationController {
    private final UserNotificationService userNotificationService;

    public UserNotificationController(UserNotificationService userNotificationService) {
        this.userNotificationService = userNotificationService;
    }

    @GetMapping("/list")
    public ResponseResult list(Integer pageNum, Integer pageSize) {
        return userNotificationService.inbox(pageNum, pageSize);
    }

    @GetMapping("/unread-count")
    public ResponseResult unreadCount() {
        return userNotificationService.unreadCount();
    }

    @PutMapping("/{id}/read")
    public ResponseResult markRead(@PathVariable Long id) {
        return userNotificationService.markRead(id);
    }

    @PutMapping("/read-all")
    public ResponseResult markAllRead() {
        return userNotificationService.markAllRead();
    }

    @DeleteMapping("/{id}")
    public ResponseResult delete(@PathVariable Long id) {
        return userNotificationService.delete(id);
    }
}
