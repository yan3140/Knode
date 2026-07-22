package com.yan.blog.controller;

import com.yan.domain.ResponseResult;
import com.yan.service.LinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/link", "/api/blog/link"})
public class LinkController {
    @Autowired
    private LinkService linkService;

    @GetMapping("/getAllLink")
    private ResponseResult getAllLink() {
        return linkService.getAllLink();
    }
}
