package com.yan.controller;


import com.yan.domain.ResponseResult;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.dto.AddCommentDto;
import com.yan.domain.entity.Comment;
import com.yan.service.CommentService;
import com.yan.utils.BeanCopyUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/comment")
@Tag(name = "评论", description = "评论相关接口")
public class CommentController {
    @Autowired
    private CommentService commentService;


    @GetMapping("/commentList")
    public ResponseResult commentList(Long articleId, Integer pageNum, Integer pageSize) {
        return commentService.commentList(SystemConstants.ARTICLE_COMMENT, articleId, pageNum, pageSize);
    }

    @PostMapping
    public ResponseResult addComment(@RequestBody AddCommentDto addCommentDto) {
        Comment comment = BeanCopyUtils.copyBean(addCommentDto, Comment.class);
        return commentService.addComment(comment);
    }

    @GetMapping("/linkCommentList")
    @Operation(summary = "友链评论列表", description = "获取所有友链评论")
    @Parameters({
            @Parameter(name = "pageNum", description = "页码"),
            @Parameter(name = "pageSize", description = "每页大小")
    })
    public ResponseResult linkCommentList(Integer pageNum, Integer pageSize) {
        return commentService.commentList(SystemConstants.LINK_COMMENT, null, pageNum, pageSize);
    }
}
