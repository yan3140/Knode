package com.yan.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import java.util.List;

class KnowledgePersonalDocumentFacadeTest {
    @Test
    void createsVersionedPersonalDocumentAndIndexTask() {
        KnowledgeBaseFacade base = mock(KnowledgeBaseFacade.class);
        KnowledgeDocumentMapper docs = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
        KnowledgeBaseDocumentMapper relations = mock(KnowledgeBaseDocumentMapper.class);
        TaskGateway tasks = mock(TaskGateway.class);
        doAnswer(i -> { i.getArgument(0, KnowledgeDocumentEntity.class).setId(11L); return 1; }).when(docs).insert(any(KnowledgeDocumentEntity.class));
        doAnswer(i -> { i.getArgument(0, KnowledgeDocumentVersionEntity.class).setId(21L); return 1; }).when(versions).insert(any(KnowledgeDocumentVersionEntity.class));
        when(relations.insert(any(KnowledgeBaseDocumentEntity.class))).thenReturn(1);
        KnowledgePersonalDocumentFacade facade = new KnowledgePersonalDocumentFacade(base, docs, versions, relations,
                tasks, new KnowledgeIndexProperties(), new KnowledgeEmbeddingProperties());

        assertThat(facade.create(3L, 5L, "并发笔记", "# 线程池\n正文")).isEqualTo(11L);
        ArgumentCaptor<KnowledgeDocumentVersionEntity> version = ArgumentCaptor.forClass(KnowledgeDocumentVersionEntity.class);
        verify(versions).insert(version.capture());
        assertThat(version.getValue().getVersionNo()).isEqualTo(1);
        assertThat(version.getValue().getEmbeddingDimensions()).isEqualTo(1024);
        ArgumentCaptor<IndexTask> task = ArgumentCaptor.forClass(IndexTask.class);
        verify(tasks).enqueue(task.capture());
        assertThat(task.getValue().taskKey()).isEqualTo("KNOWLEDGE_INDEX:11:21");
    }

    @Test
    void updatesWithNewVersionAndKeepsSameContentIdempotent() {
        KnowledgeBaseFacade base = mock(KnowledgeBaseFacade.class);
        KnowledgeDocumentMapper docs = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class);
        KnowledgeBaseDocumentMapper relations = mock(KnowledgeBaseDocumentMapper.class);
        TaskGateway tasks = mock(TaskGateway.class);
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity(); document.setId(11L); document.setSourceType("USER_DOCUMENT"); document.setSourceUri("knowledge:5:document:x");
        when(relations.selectCount(any())).thenReturn(1L); when(docs.selectById(11L)).thenReturn(document);
        KnowledgeDocumentVersionEntity latest = new KnowledgeDocumentVersionEntity(); latest.setVersionNo(1); latest.setContentHash("different");
        when(versions.selectOne(any())).thenReturn(latest);
        doAnswer(i -> { i.getArgument(0, KnowledgeDocumentVersionEntity.class).setId(22L); return 1; }).when(versions).insert(any(KnowledgeDocumentVersionEntity.class));
        KnowledgePersonalDocumentFacade facade = new KnowledgePersonalDocumentFacade(base, docs, versions, relations, tasks, new KnowledgeIndexProperties(), new KnowledgeEmbeddingProperties());
        assertThat(facade.update(3L, 5L, 11L, "新标题", "新正文")).isTrue();
        ArgumentCaptor<KnowledgeDocumentVersionEntity> version = ArgumentCaptor.forClass(KnowledgeDocumentVersionEntity.class); verify(versions).insert(version.capture());
        assertThat(version.getValue().getVersionNo()).isEqualTo(2);
    }

    @Test
    void deletesAssociationAndEnqueuesEveryVersionForRemoval() {
        KnowledgeBaseFacade base = mock(KnowledgeBaseFacade.class); KnowledgeDocumentMapper docs = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class); KnowledgeBaseDocumentMapper relations = mock(KnowledgeBaseDocumentMapper.class); TaskGateway tasks = mock(TaskGateway.class);
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity(); document.setId(11L); document.setSourceType("USER_DOCUMENT");
        KnowledgeDocumentVersionEntity first = version(21L); KnowledgeDocumentVersionEntity second = version(22L);
        when(relations.selectCount(any())).thenReturn(1L); when(docs.selectById(11L)).thenReturn(document); when(versions.selectList(any())).thenReturn(List.of(first, second)); when(relations.delete(any())).thenReturn(1);
        KnowledgePersonalDocumentFacade facade = new KnowledgePersonalDocumentFacade(base, docs, versions, relations, tasks, new KnowledgeIndexProperties(), new KnowledgeEmbeddingProperties());
        assertThat(facade.delete(3L, 5L, 11L)).isTrue();
        ArgumentCaptor<IndexTask> task = ArgumentCaptor.forClass(IndexTask.class); verify(tasks, org.mockito.Mockito.times(2)).enqueue(task.capture());
        assertThat(task.getAllValues()).extracting(IndexTask::taskKey).containsExactly("KNOWLEDGE_DELETE:11:21", "KNOWLEDGE_DELETE:11:22");
    }

    @Test
    void returnsHistoryOnlyAfterDocumentBelongsToCurrentKnowledgeBase() {
        KnowledgeBaseFacade base = mock(KnowledgeBaseFacade.class); KnowledgeDocumentMapper docs = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentVersionMapper versions = mock(KnowledgeDocumentVersionMapper.class); KnowledgeBaseDocumentMapper relations = mock(KnowledgeBaseDocumentMapper.class); TaskGateway tasks = mock(TaskGateway.class);
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity(); document.setId(11L); document.setSourceType("USER_DOCUMENT");
        when(relations.selectCount(any())).thenReturn(1L); when(docs.selectById(11L)).thenReturn(document); when(versions.selectList(any())).thenReturn(List.of(version(22L), version(21L)));
        KnowledgePersonalDocumentFacade facade = new KnowledgePersonalDocumentFacade(base, docs, versions, relations, tasks, new KnowledgeIndexProperties(), new KnowledgeEmbeddingProperties());
        assertThat(facade.history(3L, 5L, 11L)).extracting(KnowledgeDocumentVersionEntity::getId).containsExactly(22L, 21L);
    }

    private KnowledgeDocumentVersionEntity version(Long id) {
        KnowledgeDocumentVersionEntity version = new KnowledgeDocumentVersionEntity(); version.setId(id); version.setEmbeddingProvider("zhipu-openai-compatible"); version.setEmbeddingModel("embedding-3"); version.setEmbeddingDimensions(1024); version.setChunkingPolicyVersion("markdown-heading-v1"); version.setKeywordSchemaVersion("elasticsearch-standard-v1"); return version;
    }
}
