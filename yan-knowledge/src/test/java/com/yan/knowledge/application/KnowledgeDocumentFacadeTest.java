package com.yan.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yan.domain.entity.Article;
import com.yan.knowledge.domain.entity.KnowledgeBaseDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import com.yan.knowledge.mapper.KnowledgeBaseDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentMapper;
import com.yan.knowledge.source.KnowledgeArticleChangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentFacadeTest {

    @Mock
    private KnowledgeBaseFacade knowledgeBaseFacade;
    @Mock
    private KnowledgeArticleSource articleSource;
    @Mock
    private KnowledgeArticleChangeService articleChangeService;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeBaseDocumentMapper baseDocumentMapper;

    private KnowledgeDocumentFacade facade;

    @BeforeEach
    void setUp() {
        facade = new KnowledgeDocumentFacade(knowledgeBaseFacade, articleSource, articleChangeService,
                documentMapper, baseDocumentMapper);
    }

    @Test
    void attachesPublishedArticleAfterSynchronizingItsKnowledgeVersion() {
        Article article = new Article();
        article.setId(7L);
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(11L);
        when(articleSource.findPublished(7L)).thenReturn(article);
        when(documentMapper.selectBySource("ARTICLE", "7")).thenReturn(document);
        when(baseDocumentMapper.selectCount(any())).thenReturn(0L);
        when(baseDocumentMapper.insert(any(KnowledgeBaseDocumentEntity.class))).thenReturn(1);
        doNothing().when(articleChangeService).synchronize(article);

        boolean attached = facade.attachPublishedArticle(3L, 5L, 7L);

        assertThat(attached).isTrue();
        verify(knowledgeBaseFacade).ownedPrivate(3L, 5L);
        verify(articleChangeService).synchronize(article);
        verify(baseDocumentMapper).insert(any(KnowledgeBaseDocumentEntity.class));
    }

    @Test
    void doesNotCreateDuplicateAssociation() {
        Article article = new Article();
        article.setId(7L);
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(11L);
        when(articleSource.findPublished(7L)).thenReturn(article);
        when(documentMapper.selectBySource("ARTICLE", "7")).thenReturn(document);
        when(baseDocumentMapper.selectCount(any())).thenReturn(1L);

        assertThat(facade.attachPublishedArticle(3L, 5L, 7L)).isFalse();

        verify(baseDocumentMapper, never()).insert(any(KnowledgeBaseDocumentEntity.class));
    }

    @Test
    void rejectsUnavailableArticleBeforeAnyKnowledgeWrite() {
        when(articleSource.findPublished(7L)).thenReturn(null);

        assertThatThrownBy(() -> facade.attachPublishedArticle(3L, 5L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Article is not published or does not exist");

        verify(articleChangeService, never()).synchronize(any());
    }
}
