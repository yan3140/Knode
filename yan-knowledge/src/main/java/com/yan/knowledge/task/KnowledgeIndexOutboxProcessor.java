package com.yan.knowledge.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yan.knowledge.core.domain.DocumentVersionStatus;
import com.yan.knowledge.core.domain.IndexAvailability;
import com.yan.knowledge.core.domain.IndexAvailabilityPolicy;
import com.yan.knowledge.core.domain.IndexAvailabilityState;
import com.yan.knowledge.core.domain.IndexIdentity;
import com.yan.knowledge.core.domain.IndexOperation;
import com.yan.knowledge.core.domain.IndexProfile;
import com.yan.knowledge.core.domain.IndexTask;
import com.yan.knowledge.core.domain.KnowledgeChunk;
import com.yan.knowledge.core.domain.KnowledgeDocumentVersion;
import com.yan.knowledge.core.ingest.ChunkDraft;
import com.yan.knowledge.core.ingest.ChunkingPolicy;
import com.yan.knowledge.core.ingest.MarkdownChunker;
import com.yan.knowledge.core.ingest.ParsedDocument;
import com.yan.knowledge.core.port.KnowledgeIndexer;
import com.yan.knowledge.embedding.KnowledgeEmbeddingProperties;
import com.yan.knowledge.domain.entity.KnowledgeChunkEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentVersionEntity;
import com.yan.knowledge.domain.entity.KnowledgeIndexOutboxEvent;
import com.yan.knowledge.mapper.KnowledgeChunkMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.yan.knowledge.mapper.KnowledgeIndexOutboxMapper;
import com.yan.reliabletask.ReliableTaskPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexOutboxProcessor {

    private final KnowledgeIndexOutboxMapper outboxMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeIndexer indexer;
    private final KnowledgeIndexProperties indexProperties;
    private final KnowledgeEmbeddingProperties embeddingProperties;
    private final ReliableTaskPolicy taskPolicy;
    private final MarkdownChunker chunker = new MarkdownChunker();

    public KnowledgeIndexOutboxProcessor(KnowledgeIndexOutboxMapper outboxMapper,
                                         KnowledgeDocumentMapper documentMapper,
                                         KnowledgeDocumentVersionMapper versionMapper,
                                         KnowledgeChunkMapper chunkMapper,
                                         KnowledgeIndexer indexer,
                                         KnowledgeIndexProperties indexProperties,
                                         KnowledgeEmbeddingProperties embeddingProperties,
                                         KnowledgeOutboxProperties outboxProperties) {
        this.outboxMapper = outboxMapper;
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.chunkMapper = chunkMapper;
        this.indexer = indexer;
        this.indexProperties = indexProperties;
        this.embeddingProperties = embeddingProperties;
        this.taskPolicy = new ReliableTaskPolicy(outboxProperties.getMaxRetry(),
                outboxProperties.getRetryDelaySeconds(), outboxProperties.getMaxRetryDelaySeconds());
    }

    public void process(List<KnowledgeIndexOutboxEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (KnowledgeIndexOutboxEvent event : events) {
            outboxMapper.markProcessingStarted(event.getId());
            try {
                process(event);
                outboxMapper.markSucceeded(event.getId());
            } catch (RuntimeException failure) {
                outboxMapper.markForRetry(event.getId(), taskPolicy.nextRetryDelaySeconds(event.getRetryCount()),
                        taskPolicy.safeError(failure.getMessage()));
            }
        }
    }

    public void markForRetry(KnowledgeIndexOutboxEvent event, String reason) {
        outboxMapper.markForRetry(event.getId(), taskPolicy.nextRetryDelaySeconds(event.getRetryCount()),
                taskPolicy.safeError(reason));
    }

    private void process(KnowledgeIndexOutboxEvent event) {
        KnowledgeDocumentVersionEntity version = versionMapper.selectById(event.getDocumentVersionId());
        if (version == null) {
            return;
        }
        KnowledgeDocumentEntity document = documentMapper.selectById(event.getDocumentId());
        if (document == null) {
            return;
        }
        IndexOperation operation = IndexOperation.valueOf(event.getOperation());
        IndexTask task = task(event, version);
        KnowledgeDocumentVersion coreVersion = coreVersion(version);
        if (operation == IndexOperation.DELETE) {
            indexer.delete(coreVersion, task);
            chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
                    .eq(KnowledgeChunkEntity::getDocumentVersionId, version.getId()));
            version.setStatus(DocumentVersionStatus.DELETED.name());
            versionMapper.updateById(version);
            documentMapper.clearCurrentVersion(document.getId(), version.getId());
            return;
        }

        version.setStatus(DocumentVersionStatus.INDEXING.name());
        versionMapper.updateById(version);
        List<KnowledgeChunk> chunks = chunks(document, version);
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkEntity>()
                .eq(KnowledgeChunkEntity::getDocumentVersionId, version.getId()));
        chunks.forEach(chunk -> chunkMapper.insert(chunkEntity(chunk)));
        indexer.index(coreVersion, chunks, task);

        IndexProfile profile = new IndexProfile(version.getKeywordEnabled() == 1, version.getVectorEnabled() == 1);
        IndexAvailabilityState vectorState = !profile.vectorEnabled() ? IndexAvailabilityState.NOT_REQUIRED
                : embeddingProperties.getApiKey() == null || embeddingProperties.getApiKey().isBlank()
                ? IndexAvailabilityState.FAILED : IndexAvailabilityState.READY;
        IndexAvailability availability = IndexAvailability.from(profile, IndexAvailabilityState.READY, vectorState);
        DocumentVersionStatus resolved = new IndexAvailabilityPolicy(indexProperties.isRequireKeyword(),
                indexProperties.isRequireVector(), indexProperties.isAllowPartial()).resolveStatus(availability);
        version.setKeywordAvailability(availability.keyword().name());
        version.setVectorAvailability(availability.vector().name());
        version.setStatus(resolved.name());
        versionMapper.updateById(version);
        if (resolved.isUsable()) {
            documentMapper.publishVersion(document.getId(), version.getId());
        }
    }

    private List<KnowledgeChunk> chunks(KnowledgeDocumentEntity document, KnowledgeDocumentVersionEntity version) {
        List<ChunkDraft> drafts = chunker.chunk(new ParsedDocument(version.getTitle(), version.getSourceUri(),
                version.getContent()), new ChunkingPolicy(700, 100, version.getChunkingPolicyVersion()));
        List<KnowledgeChunk> chunks = new ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            Map<String, String> metadata = new java.util.HashMap<>(draft.metadata());
            metadata.put("sourceType", document.getSourceType());
            metadata.put("articleId", document.getSourceId());
            chunks.add(new KnowledgeChunk(KnowledgeChunk.stableId(document.getId().toString(), version.getId().toString(),
                    draft.chunkIndex()), document.getId().toString(), version.getId().toString(), draft.chunkIndex(),
                    draft.content(), version.getTitle(), draft.section(), version.getSourceUri(), metadata));
        }
        return List.copyOf(chunks);
    }

    private KnowledgeChunkEntity chunkEntity(KnowledgeChunk chunk) {
        KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
        entity.setId(chunk.id());
        entity.setDocumentId(Long.valueOf(chunk.documentId()));
        entity.setDocumentVersionId(Long.valueOf(chunk.documentVersionId()));
        entity.setChunkIndex(chunk.chunkIndex());
        entity.setTitle(chunk.title());
        entity.setSectionName(chunk.section());
        entity.setContent(chunk.content());
        entity.setSourceUri(chunk.source());
        return entity;
    }

    private KnowledgeDocumentVersion coreVersion(KnowledgeDocumentVersionEntity version) {
        IndexProfile profile = new IndexProfile(version.getKeywordEnabled() == 1, version.getVectorEnabled() == 1);
        IndexIdentity identity = new IndexIdentity(version.getEmbeddingProvider(), version.getEmbeddingModel(),
                version.getEmbeddingDimensions(), version.getChunkingPolicyVersion(), version.getKeywordSchemaVersion());
        IndexAvailability availability = IndexAvailability.from(profile,
                IndexAvailabilityState.valueOf(version.getKeywordAvailability()),
                IndexAvailabilityState.valueOf(version.getVectorAvailability()));
        return new KnowledgeDocumentVersion(version.getId().toString(), version.getDocumentId().toString(),
                version.getVersionNo(), version.getContentHash(), version.getTitle(), version.getContent(),
                version.getSourceUri(), DocumentVersionStatus.valueOf(version.getStatus()), profile, identity,
                availability, Instant.now());
    }

    private IndexTask task(KnowledgeIndexOutboxEvent event, KnowledgeDocumentVersionEntity version) {
        return new IndexTask(event.getTaskKey(), event.getDocumentId().toString(), event.getDocumentVersionId().toString(),
                IndexOperation.valueOf(event.getOperation()), new IndexIdentity(version.getEmbeddingProvider(),
                version.getEmbeddingModel(), version.getEmbeddingDimensions(), version.getChunkingPolicyVersion(),
                version.getKeywordSchemaVersion()), Instant.now());
    }
}
