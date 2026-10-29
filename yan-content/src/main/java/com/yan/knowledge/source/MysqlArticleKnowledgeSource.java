package com.yan.knowledge.source;

import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Article;
import com.yan.knowledge.application.KnowledgeArticleSource;
import com.yan.mapper.ArticleMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class MysqlArticleKnowledgeSource implements KnowledgeArticleSource {

    private final ArticleMapper articleMapper;

    public MysqlArticleKnowledgeSource(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
    }

    @Override
    public Article findPublished(Long articleId) {
        if (articleId == null || articleId < 1) {
            return null;
        }
        Article article = articleMapper.selectById(articleId);
        if (article == null || !Objects.equals(String.valueOf(SystemConstants.ARTICLE_STATUS_NORMAL), article.getStatus())
                || (article.getDelFlag() != null && article.getDelFlag() != 0)) {
            return null;
        }
        return article;
    }
}
