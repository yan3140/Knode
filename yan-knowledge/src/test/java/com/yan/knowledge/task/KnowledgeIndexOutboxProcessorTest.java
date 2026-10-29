package com.yan.knowledge.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yan.knowledge.core.domain.KnowledgeChunk;
import com.yan.knowledge.core.domain.KnowledgeDocumentVersion;
import com.yan.knowledge.core.port.KnowledgeIndexer;
import com.yan.knowledge.embedding.KnowledgeEmbeddingProperties;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentVersionEntity;
import com.yan.knowledge.domain.entity.KnowledgeIndexOutboxEvent;
import com.yan.knowledge.mapper.KnowledgeChunkMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.yan.knowledge.mapper.KnowledgeIndexOutboxMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeIndexOutboxProcessorTest {

    @Mock
    private KnowledgeIndexOutboxMapper outboxMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeDocumentVersionMapper versionMapper;
    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private KnowledgeIndexer indexer;

    private KnowledgeIndexOutboxProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new KnowledgeIndexOutboxProcessor(outboxMapper, documentMapper, versionMapper, chunkMapper,
                indexer, new KnowledgeIndexProperties(), new KnowledgeEmbeddingProperties(), new KnowledgeOutboxProperties());
    }

    @Test
    void indexesKeywordChunksAndPublishesPartialReadyVersion() {
        KnowledgeIndexOutboxEvent event = event();
        KnowledgeDocumentVersionEntity version = version();
        when(versionMapper.selectById(21L)).thenReturn(version);
        when(documentMapper.selectById(11L)).thenReturn(document());

        processor.process(List.of(event));

        ArgumentCaptor<List<KnowledgeChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(indexer).index(any(KnowledgeDocumentVersion.class), chunks.capture(), any());
        assertThat(chunks.getValue()).hasSize(1);
        assertThat(chunks.getValue().get(0).id()).isEqualTo("11:21:0");
        assertThat(version.getStatus()).isEqualTo("PARTIAL_READY");
        assertThat(version.getKeywordAvailability()).isEqualTo("READY");
        assertThat(version.getVectorAvailability()).isEqualTo("FAILED");
        verify(documentMapper).publishVersion(11L, 21L);
        verify(outboxMapper).markSucceeded(1L);
    }

    @Test
    void schedulesRetryWhenIndexWriteFails() {
        KnowledgeIndexOutboxEvent event = event();
        when(versionMapper.selectById(21L)).thenReturn(version());
        when(documentMapper.selectById(11L)).thenReturn(document());
        doThrow(new IllegalStateException("Elasticsearch unavailable"))
                .when(indexer).index(any(), any(), any());

        processor.process(List.of(event));

        verify(outboxMapper).markForRetry(eq(1L), eq(10), eq("Elasticsearch unavailable"));
        verify(outboxMapper, never()).markSucceeded(1L);
    }

    private KnowledgeIndexOutboxEvent event() {
        KnowledgeIndexOutboxEvent event = new KnowledgeIndexOutboxEvent();
        event.setId(1L);
        event.setDocumentId(11L);
        event.setDocumentVersionId(21L);
        event.setOperation("INDEX");
        event.setTaskKey("KNOWLEDGE_INDEX:11:21");
        event.setRetryCount(0);
        return event;
    }

    private KnowledgeDocumentEntity document() {
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setId(11L);
        document.setSourceType("ARTICLE");
        document.setSourceId("7");
        return document;
    }

    private KnowledgeDocumentVersionEntity version() {
        KnowledgeDocumentVersionEntity version = new KnowledgeDocumentVersionEntity();
        version.setId(21L);
        version.setDocumentId(11L);
        version.setVersionNo(1);
        version.setContentHash("a".repeat(64));
        version.setTitle("Java concurrency");
        version.setContent("ExecutorService configuration");
        version.setSourceUri("article:7");
        version.setStatus("RECEIVED");
        version.setKeywordEnabled(1);
        version.setVectorEnabled(1);
        version.setKeywordAvailability("PENDING");
        version.setVectorAvailability("PENDING");
        version.setEmbeddingProvider("unconfigured");
        version.setEmbeddingModel("unconfigured");
        version.setEmbeddingDimensions(0);
        version.setChunkingPolicyVersion("markdown-heading-v1");
        version.setKeywordSchemaVersion("elasticsearch-standard-v1");
        return version;
    }
}
