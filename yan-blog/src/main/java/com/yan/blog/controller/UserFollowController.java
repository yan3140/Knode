package com.yan.blog.controller;

import com.yan.domain.ResponseResult;
import com.yan.service.UserFollowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/follow", "/api/blog/follow"})
public class UserFollowController {
    private final UserFollowService userFollowService;

    public UserFollowController(UserFollowService userFollowService) {
        this.userFollowService = userFollowService;
    }

    @PutMapping("/{followeeId}")
    public ResponseResult follow(@PathVariable Long followeeId) {
        return userFollowService.follow(followeeId);
    }

    @DeleteMapping("/{followeeId}")
    public ResponseResult unfollow(@PathVariable Long followeeId) {
        return userFollowService.unfollow(followeeId);
    }

    @GetMapping("/status/{followeeId}")
    public ResponseResult status(@PathVariable Long followeeId) {
        return userFollowService.followingStatus(followeeId);
    }

    @GetMapping("/following")
    public ResponseResult following(Integer pageNum, Integer pageSize) {
        return userFollowService.following(pageNum, pageSize);
    }

    @GetMapping("/followers")
    public ResponseResult followers(Integer pageNum, Integer pageSize) {
        return userFollowService.followers(pageNum, pageSize);
    }

    @GetMapping("/{userId}/following")
    public ResponseResult followingOf(@PathVariable Long userId, Integer pageNum, Integer pageSize) {
        return userFollowService.followingOf(userId, pageNum, pageSize);
    }
}
