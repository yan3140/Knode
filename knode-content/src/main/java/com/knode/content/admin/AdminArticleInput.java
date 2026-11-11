package com.knode.content.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.knode.content.article.ArticleCommand;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown=true)
public record AdminArticleInput(Long id,String title,String thumbnail,String isTop,String isComment,String content,
                                List<Long> tags,Long categoryId,String summary,String status) {
    public ArticleCommand command() { return new ArticleCommand(title,thumbnail,isComment,content,tags,categoryId,summary,status); }
}
