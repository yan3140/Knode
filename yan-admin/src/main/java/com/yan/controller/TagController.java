package com.yan.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.dto.TagDto;
import com.yan.domain.dto.UpdateTagDto;
import com.yan.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/content/tag")
public class TagController {
    @Autowired
    private TagService tagService;

    @GetMapping("/list")
    public ResponseResult getTagList(Integer pageNum, Integer pageSize, TagDto tagDto) {
        return tagService.getTagList(pageNum,pageSize,tagDto);
    }

    @PostMapping
    public ResponseResult addTag(@RequestBody UpdateTagDto uptagDto) {
        return tagService.addTag(uptagDto);
    }

    @DeleteMapping("/{ids}")
    public ResponseResult deleteTag(@PathVariable List<Long> ids) {
        return tagService.deleteTag(ids);
    }

    @GetMapping("/{id}")
    public ResponseResult getTagById(@PathVariable Long id) {
        return tagService.getTagById(id);
    }

    @PutMapping
    public ResponseResult updateTag(@RequestBody UpdateTagDto updateTagDto) {
        return tagService.updateTag(updateTagDto);
    }

    @GetMapping("/listAllTag")
    public ResponseResult listAllTag() {
        return tagService.listAllTag();
    }


}
