package com.yan.knowledge.application;

import com.yan.knowledge.core.domain.DocumentVersionStatus;
import com.yan.knowledge.core.domain.IndexAvailabilityState;
import com.yan.knowledge.core.domain.IndexIdentity;
import com.yan.knowledge.core.domain.IndexOperation;
import com.yan.knowledge.core.domain.IndexProfile;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgePersonalDocumentFacade {

    private static final String USER_DOCUMENT_SOURCE = "USER_DOCUMENT";
    private final KnowledgeBaseFacade baseFacade;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeBaseDocumentMapper baseDocumentMapper;
    private final TaskGateway taskGateway;
    private final KnowledgeIndexProperties indexProperties;
    private final KnowledgeEmbeddingProperties embeddingProperties;

    public KnowledgePersonalDocumentFacade(KnowledgeBaseFacade baseFacade, KnowledgeDocumentMapper documentMapper,
                                            KnowledgeDocumentVersionMapper versionMapper,
                                            KnowledgeBaseDocumentMapper baseDocumentMapper, TaskGateway taskGateway,
                                            KnowledgeIndexProperties indexProperties,
                                            KnowledgeEmbeddingProperties embeddingProperties) {
        this.baseFacade = baseFacade; this.documentMapper = documentMapper; this.versionMapper = versionMapper;
        this.baseDocumentMapper = baseDocumentMapper; this.taskGateway = taskGateway;
        this.indexProperties = indexProperties; this.embeddingProperties = embeddingProperties;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long ownerId, Long knowledgeBaseId, String title, String content) {
        baseFacade.ownedPrivate(ownerId, knowledgeBaseId);
        String safeTitle = require(title, "Title", 255);
        String safeContent = require(content, "Content", 1_000_000);
        String sourceId = ownerId + ":" + UUID.randomUUID();
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setSourceType(USER_DOCUMENT_SOURCE); document.setSourceId(sourceId);
        document.setSourceUri("knowledge:" + knowledgeBaseId + ":document:" + sourceId);
        if (documentMapper.insert(document) != 1 || document.getId() == null) throw new IllegalStateException("Unable to create personal knowledge document");
        KnowledgeBaseDocumentEntity relation = new KnowledgeBaseDocumentEntity();
        relation.setKnowledgeBaseId(knowledgeBaseId); relation.setDocumentId(document.getId());
        if (baseDocumentMapper.insert(relation) != 1) throw new IllegalStateException("Unable to link personal knowledge document");
        KnowledgeDocumentVersionEntity version = newVersion(document, 1, safeTitle, safeContent);
        persistAndEnqueue(document, version);
        return document.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean update(Long ownerId, Long knowledgeBaseId, Long documentId, String title, String content) {
        baseFacade.ownedPrivate(ownerId, knowledgeBaseId);
        KnowledgeDocumentEntity document = ownedDocument(knowledgeBaseId, documentId);
        String safeTitle = require(title, "Title", 255);
        String safeContent = require(content, "Content", 1_000_000);
        KnowledgeDocumentVersionEntity latest = versionMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentVersionEntity>()
                .eq(KnowledgeDocumentVersionEntity::getDocumentId, documentId).orderByDesc(KnowledgeDocumentVersionEntity::getVersionNo).last("LIMIT 1"));
        String nextHash = hash(safeTitle + "\u0000" + safeContent);
        if (latest != null && nextHash.equals(latest.getContentHash())) return false;
        KnowledgeDocumentVersionEntity version = newVersion(document, latest == null ? 1 : latest.getVersionNo() + 1, safeTitle, safeContent);
        persistAndEnqueue(document, version);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long ownerId, Long knowledgeBaseId, Long documentId) {
        baseFacade.ownedPrivate(ownerId, knowledgeBaseId);
        KnowledgeDocumentEntity document = ownedDocument(knowledgeBaseId, documentId);
        List<KnowledgeDocumentVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentVersionEntity>()
                .eq(KnowledgeDocumentVersionEntity::getDocumentId, documentId));
        for (KnowledgeDocumentVersionEntity version : versions) {
            IndexIdentity identity = new IndexIdentity(version.getEmbeddingProvider(), version.getEmbeddingModel(), version.getEmbeddingDimensions(), version.getChunkingPolicyVersion(), version.getKeywordSchemaVersion());
            taskGateway.enqueue(new IndexTask("KNOWLEDGE_DELETE:" + document.getId() + ":" + version.getId(), document.getId().toString(), version.getId().toString(), IndexOperation.DELETE, identity, Instant.now()));
        }
        return baseDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeBaseDocumentEntity>()
                .eq(KnowledgeBaseDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeBaseDocumentEntity::getDocumentId, documentId)) > 0;
    }

    public List<KnowledgeDocumentVersionEntity> history(Long ownerId, Long knowledgeBaseId, Long documentId) {
        baseFacade.ownedPrivate(ownerId, knowledgeBaseId);
        ownedDocument(knowledgeBaseId, documentId);
        return versionMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentVersionEntity>()
                .eq(KnowledgeDocumentVersionEntity::getDocumentId, documentId)
                .orderByDesc(KnowledgeDocumentVersionEntity::getVersionNo));
    }

    private KnowledgeDocumentEntity ownedDocument(Long knowledgeBaseId, Long documentId) {
        if (documentId == null || documentId < 1) throw new IllegalArgumentException("Document id must be positive");
        Long count = baseDocumentMapper.selectCount(new LambdaQueryWrapper<KnowledgeBaseDocumentEntity>()
                .eq(KnowledgeBaseDocumentEntity::getKnowledgeBaseId, knowledgeBaseId).eq(KnowledgeBaseDocumentEntity::getDocumentId, documentId));
        KnowledgeDocumentEntity document = documentMapper.selectById(documentId);
        if (count == null || count == 0 || document == null || !USER_DOCUMENT_SOURCE.equals(document.getSourceType())) throw new IllegalArgumentException("Document is not owned by the current knowledge base");
        return document;
    }

    private KnowledgeDocumentVersionEntity newVersion(KnowledgeDocumentEntity document, int versionNo, String title, String content) {
        IndexProfile profile = indexProperties.indexProfile();
        IndexIdentity identity = embeddingProperties.indexIdentity(indexProperties.getChunkingPolicyVersion(), indexProperties.getKeywordSchemaVersion());
        KnowledgeDocumentVersionEntity version = new KnowledgeDocumentVersionEntity();
        version.setDocumentId(document.getId()); version.setVersionNo(versionNo); version.setContentHash(hash(title + "\u0000" + content));
        version.setTitle(title); version.setContent(content); version.setSourceUri(document.getSourceUri());
        version.setStatus(DocumentVersionStatus.RECEIVED.name());
        version.setKeywordEnabled(profile.keywordEnabled() ? 1 : 0); version.setVectorEnabled(profile.vectorEnabled() ? 1 : 0);
        version.setKeywordAvailability(profile.keywordEnabled() ? IndexAvailabilityState.PENDING.name() : IndexAvailabilityState.NOT_REQUIRED.name());
        version.setVectorAvailability(profile.vectorEnabled() ? IndexAvailabilityState.PENDING.name() : IndexAvailabilityState.NOT_REQUIRED.name());
        version.setEmbeddingProvider(identity.embeddingProvider()); version.setEmbeddingModel(identity.embeddingModel());
        version.setEmbeddingDimensions(identity.embeddingDimensions()); version.setChunkingPolicyVersion(identity.chunkingPolicyVersion());
        version.setKeywordSchemaVersion(identity.keywordSchemaVersion());
        return version;
    }
    private void persistAndEnqueue(KnowledgeDocumentEntity document, KnowledgeDocumentVersionEntity version) {
        if (versionMapper.insert(version) != 1 || version.getId() == null) throw new IllegalStateException("Unable to create personal knowledge document version");
        IndexIdentity identity = new IndexIdentity(version.getEmbeddingProvider(), version.getEmbeddingModel(), version.getEmbeddingDimensions(), version.getChunkingPolicyVersion(), version.getKeywordSchemaVersion());
        taskGateway.enqueue(new IndexTask("KNOWLEDGE_INDEX:" + document.getId() + ":" + version.getId(), document.getId().toString(), version.getId().toString(), IndexOperation.INDEX, identity, Instant.now()));
    }

    private String require(String value, String field, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) throw new IllegalArgumentException(field + " must contain 1 to " + max + " characters");
        return value.strip();
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
