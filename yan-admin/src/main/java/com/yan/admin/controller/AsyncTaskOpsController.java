package com.yan.admin.controller;

import com.yan.domain.ResponseResult;
import com.yan.operations.AsyncTaskOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/ops/outbox", "/api/admin/ops/outbox"})
public class AsyncTaskOpsController {
    private final AsyncTaskOpsService asyncTaskOpsService;

    public AsyncTaskOpsController(AsyncTaskOpsService asyncTaskOpsService) {
        this.asyncTaskOpsService = asyncTaskOpsService;
    }

    @GetMapping("/summary")
    public ResponseResult summary() {
        return asyncTaskOpsService.summary();
    }

    @GetMapping("/search")
    public ResponseResult searchEvents(Integer pageNum, Integer pageSize, String status) {
        return asyncTaskOpsService.searchEvents(pageNum, pageSize, status);
    }

    @GetMapping("/notification")
    public ResponseResult notificationEvents(Integer pageNum, Integer pageSize, String status) {
        return asyncTaskOpsService.notificationEvents(pageNum, pageSize, status);
    }

    @PostMapping("/search/retry")
    public ResponseResult replaySearchPending() {
        return asyncTaskOpsService.replaySearchPending();
    }

    @PostMapping("/notification/retry")
    public ResponseResult replayNotificationPending() {
        return asyncTaskOpsService.replayNotificationPending();
    }

    @PostMapping("/retry")
    public ResponseResult replayAllPending() {
        return asyncTaskOpsService.replayAllPending();
    }
}
