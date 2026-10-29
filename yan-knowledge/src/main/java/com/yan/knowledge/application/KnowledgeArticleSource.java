package com.yan.knowledge.application;

import com.yan.domain.entity.Article;

/** Reads only public, published articles that a private knowledge base may reference. */
public interface KnowledgeArticleSource {

    Article findPublished(Long articleId);
}
