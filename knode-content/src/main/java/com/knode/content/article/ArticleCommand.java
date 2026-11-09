package com.knode.content.article;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleCommand(String title, String thumbnail, String isComment, String content,
                             List<Long> tags, Long categoryId, String summary, String status) {}
