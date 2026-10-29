package com.yan.knowledge.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yan.domain.entity.Article;
import com.yan.knowledge.core.domain.IndexOperation;
import com.yan.knowledge.core.domain.IndexTask;
import com.yan.knowledge.core.port.TaskGateway;
import com.yan.knowledge.domain.entity.KnowledgeBaseDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentVersionEntity;
import com.yan.knowledge.embedding.KnowledgeEmbeddingProperties;
import com.yan.knowledge.mapper.KnowledgeBaseDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.yan.knowledge.task.KnowledgeIndexProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeArticleChangeServiceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeDocumentVersionMapper versionMapper;
    @Mock
    private KnowledgeBaseDocumentMapper baseDocumentMapper;
    @Mock
    private TaskGateway taskGateway;

    private KnowledgeArticleChangeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeArticleChangeService(documentMapper, versionMapper, baseDocumentMapper,
                taskGateway, new KnowledgeIndexProperties(), new KnowledgeEmbeddingProperties());
    }

    @Test
    void createsImmutableVersionAndIndexTaskForPublishedArticle() {
        Article article = article(7L, "0");
        when(documentMapper.selectBySource("ARTICLE", "7")).thenReturn(null);
        when(versionMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            KnowledgeDocumentEntity document = invocation.getArgument(0);
            document.setId(11L);
            return 1;
        }).when(documentMapper).insert(any(KnowledgeDocumentEntity.class));
        when(baseDocumentMapper.insert(any(KnowledgeBaseDocumentEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            KnowledgeDocumentVersionEntity version = invocation.getArgument(0);
            version.setId(21L);
            return 1;
        }).when(versionMapper).insert(any(KnowledgeDocumentVersionEntity.class));

        service.synchronize(article);

        ArgumentCaptor<KnowledgeDocumentVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(KnowledgeDocumentVersionEntity.class);
        verify(versionMapper).insert(versionCaptor.capture());
        KnowledgeDocumentVersionEntity version = versionCaptor.getValue();
        assertThat(version.getDocumentId()).isEqualTo(11L);
        assertThat(version.getVersionNo()).isEqualTo(1);
        assertThat(version.getStatus()).isEqualTo("RECEIVED");
        assertThat(version.getKeywordAvailability()).isEqualTo("PENDING");
        assertThat(version.getVectorAvailability()).isEqualTo("PENDING");
        assertThat(version.getEmbeddingProvider()).isEqualTo("zhipu-openai-compatible");
        assertThat(version.getEmbeddingModel()).isEqualTo("embedding-3");
        assertThat(version.getEmbeddingDimensions()).isEqualTo(1024);

        ArgumentCaptor<IndexTask> taskCaptor = ArgumentCaptor.forClass(IndexTask.class);
        verify(taskGateway).enqueue(taskCaptor.capture());
        assertThat(taskCaptor.getValue().taskKey()).isEqualTo("KNOWLEDGE_INDEX:11:21");
        assertThat(taskCaptor.getValue().operation()).isEqualTo(IndexOperation.INDEX);
    }

    @Test
    void reusesMatchingImmutableVersionWithoutCreatingDuplicate() {
        Article article = article(7L, "0");
        KnowledgeDocumentEntity document = document(11L);
        KnowledgeDocumentVersionEntity existingVersion = version(21L, 11L);
        when(documentMapper.selectBySource("ARTICLE", "7")).thenReturn(document);
        when(versionMapper.selectOne(any())).thenReturn(existingVersion);

        service.synchronize(article);

        verify(versionMapper, never()).insert(any(KnowledgeDocumentVersionEntity.class));
        ArgumentCaptor<IndexTask> taskCaptor = ArgumentCaptor.forClass(IndexTask.class);
        verify(taskGateway).enqueue(taskCaptor.capture());
        assertThat(taskCaptor.getValue().taskKey()).isEqualTo("KNOWLEDGE_INDEX:11:21");
    }

    @Test
    void queuesDeletionForEveryKnownVersionWhenArticleIsNoLongerPublished() {
        KnowledgeDocumentEntity document = document(11L);
        when(documentMapper.selectBySource("ARTICLE", "7")).thenReturn(document);
        when(versionMapper.selectList(any())).thenReturn(List.of(version(21L, 11L), version(22L, 11L)));

        service.synchronize(article(7L, "1"));

        ArgumentCaptor<IndexTask> taskCaptor = ArgumentCaptor.forClass(IndexTask.class);
        verify(taskGateway, org.mockito.Mockito.times(2)).enqueue(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues())
                .extracting(IndexTask::taskKey)
                .containsExactly("KNOWLEDGE_DELETE:11:21", "KNOWLEDGE_DELETE:11:22");
        assertThat(taskCaptor.getAllValues()).allMatch(task -> task.operation() == IndexOperation.DELETE);
    }

    private Article article(Long id, String status) {
        Article article = new Article();
        article.setId(id);
        article.setStatus(status);
        article.setTitle("Java concurrency");
        article.setSummary("summary");
        article.setContent("content");
        article.setDelFlag(0);
        return article;
    }

    private KnowledgeDocumentEntity document(Long id) {
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(id);
        document.setSourceType("ARTICLE");
        document.setSourceId("7");
        return document;
    }

    private KnowledgeDocumentVersionEntity version(Long id, Long documentId) {
        KnowledgeDocumentVersionEntity version = new KnowledgeDocumentVersionEntity();
        version.setId(id);
        version.setDocumentId(documentId);
        version.setVersionNo(1);
        version.setEmbeddingProvider("unconfigured");
        version.setEmbeddingModel("unconfigured");
        version.setEmbeddingDimensions(0);
        version.setChunkingPolicyVersion("markdown-heading-v1");
        version.setKeywordSchemaVersion("elasticsearch-standard-v1");
        return version;
    }
}
