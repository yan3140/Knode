package com.yan.admin.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Link;
import com.yan.service.LinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("adminLinkController")
@RequestMapping({"/content/link", "/api/admin/content/link"})
public class LinkController {
    @Autowired
    private LinkService linkService;

    @GetMapping("/list")
    public ResponseResult list(Integer pageNum, Integer pageSize, String name, String status) {
        return linkService.listLinks(pageNum, pageSize, name, status);
    }

    @GetMapping("/{id}")
    public ResponseResult get(@PathVariable Long id) {
        return linkService.getLink(id);
    }

    @PostMapping
    public ResponseResult add(@RequestBody Link link) {
        return linkService.addLink(link);
    }

    @PutMapping
    public ResponseResult update(@RequestBody Link link) {
        return linkService.updateLink(link);
    }

    @DeleteMapping("/{ids}")
    public ResponseResult delete(@PathVariable List<Long> ids) {
        return linkService.deleteLinks(ids);
    }

    @PutMapping("/changeLinkStatus")
    public ResponseResult changeStatus(@RequestBody Link link) {
        return linkService.updateLinkStatus(link.getId(), link.getStatus());
    }
}
