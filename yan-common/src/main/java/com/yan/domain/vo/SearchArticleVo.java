package com.yan.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchArticleVo {
    private Long id;
    private String title;
    private String summary;
    private Long categoryId;
    private String categoryName;
    private List<String> tagNames;
    private String thumbnail;
    private Long viewCount;
    private Date createTime;
    private Float score;
    private Map<String, List<String>> highlights;
}
