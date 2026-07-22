package com.yan.domain.vo;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleDetailVo {
    private Long id;
    //标题
    private String title;
    //文章摘要
    private String summary;
    //分类id
    private Long categoryId;
    //所属分类
    private String categoryName;
    private String isComment;

    private String Content;
    //访问量
    private Long viewCount;
    //是否允许评论 1是，0否
    private Date createTime;
    private Long authorId;
    private String authorName;
    private String authorAvatar;
}
