package com.yan.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleDto {
    // 博客ID，新增时为空，编辑时必填
    private Long id;

    // 博客标题
    private String title;

    // 缩略图地址
    private String thumbnail;

    // 是否置顶（0=否，1=是）
    private String isTop;

    // 是否允许评论（0=否，1=是）
    private String isComment;

    // 博客内容（包含Markdown格式）
    private String content;

    // 关联的标签ID列表
    private List<Long> tags;

    // 分类ID
    private Long categoryId;

    // 博客摘要
    private String summary;

    // 博客状态（1=发布，0=草稿等）
    private String status;
}
