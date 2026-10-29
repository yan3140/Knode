package com.yan.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yan.domain.entity.Article;
import com.yan.knowledge.domain.entity.KnowledgeBaseDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import com.yan.knowledge.mapper.KnowledgeBaseDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentMapper;
import com.yan.knowledge.source.KnowledgeArticleChangeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeDocumentFacade {

    private static final String ARTICLE_SOURCE = "ARTICLE";

    private final KnowledgeBaseFacade knowledgeBaseFacade;
    private final KnowledgeArticleSource articleSource;
    private final KnowledgeArticleChangeService articleChangeService;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseDocumentMapper baseDocumentMapper;

    public KnowledgeDocumentFacade(KnowledgeBaseFacade knowledgeBaseFacade,
                                   KnowledgeArticleSource articleSource,
                                   KnowledgeArticleChangeService articleChangeService,
                                   KnowledgeDocumentMapper documentMapper,
                                   KnowledgeBaseDocumentMapper baseDocumentMapper) {
        this.knowledgeBaseFacade = knowledgeBaseFacade;
        this.articleSource = articleSource;
        this.articleChangeService = articleChangeService;
        this.documentMapper = documentMapper;
        this.baseDocumentMapper = baseDocumentMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean attachPublishedArticle(Long ownerId, Long knowledgeBaseId, Long articleId) {
        knowledgeBaseFacade.ownedPrivate(ownerId, knowledgeBaseId);
        if (articleId == null || articleId < 1) {
            throw new IllegalArgumentException("Article id must be positive");
        }
        Article article = articleSource.findPublished(articleId);
        if (article == null) {
            throw new IllegalArgumentException("Article is not published or does not exist");
        }

        articleChangeService.synchronize(article);
        KnowledgeDocumentEntity document = documentMapper.selectBySource(ARTICLE_SOURCE, articleId.toString());
        if (document == null || document.getId() == null) {
            throw new IllegalStateException("Article knowledge document was not created");
        }
        Long existing = baseDocumentMapper.selectCount(new LambdaQueryWrapper<KnowledgeBaseDocumentEntity>()
                .eq(KnowledgeBaseDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeBaseDocumentEntity::getDocumentId, document.getId()));
        if (existing != null && existing > 0) {
            return false;
        }

        KnowledgeBaseDocumentEntity relation = new KnowledgeBaseDocumentEntity();
        relation.setKnowledgeBaseId(knowledgeBaseId);
        relation.setDocumentId(document.getId());
        if (baseDocumentMapper.insert(relation) != 1) {
            throw new IllegalStateException("Unable to attach article to private knowledge base");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean detachDocument(Long ownerId, Long knowledgeBaseId, Long documentId) {
        knowledgeBaseFacade.ownedPrivate(ownerId, knowledgeBaseId);
        if (documentId == null || documentId < 1) {
            throw new IllegalArgumentException("Document id must be positive");
        }
        return baseDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeBaseDocumentEntity>()
                .eq(KnowledgeBaseDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeBaseDocumentEntity::getDocumentId, documentId)) > 0;
    }
}
