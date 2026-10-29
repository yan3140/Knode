package com.yan.knowledge.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Article;
import com.yan.knowledge.core.domain.DocumentVersionStatus;
import com.yan.knowledge.core.domain.IndexAvailabilityState;
import com.yan.knowledge.core.domain.IndexIdentity;
import com.yan.knowledge.core.domain.IndexOperation;
import com.yan.knowledge.core.domain.IndexProfile;
import com.yan.knowledge.core.domain.IndexTask;
import com.yan.knowledge.core.port.TaskGateway;
import com.yan.knowledge.domain.KnowledgeBaseIds;
import com.yan.knowledge.domain.entity.KnowledgeBaseDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeDocumentVersionEntity;
import com.yan.knowledge.embedding.KnowledgeEmbeddingProperties;
import com.yan.knowledge.mapper.KnowledgeBaseDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.yan.knowledge.task.KnowledgeIndexProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeArticleChangeService {

    private static final String ARTICLE_SOURCE = "ARTICLE";

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeBaseDocumentMapper baseDocumentMapper;
    private final TaskGateway taskGateway;
    private final KnowledgeIndexProperties indexProperties;
    private final KnowledgeEmbeddingProperties embeddingProperties;

    public KnowledgeArticleChangeService(KnowledgeDocumentMapper documentMapper,
                                         KnowledgeDocumentVersionMapper versionMapper,
                                         KnowledgeBaseDocumentMapper baseDocumentMapper,
                                         TaskGateway taskGateway,
                                         KnowledgeIndexProperties indexProperties,
                                         KnowledgeEmbeddingProperties embeddingProperties) {
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.baseDocumentMapper = baseDocumentMapper;
        this.taskGateway = taskGateway;
        this.indexProperties = indexProperties;
        this.embeddingProperties = embeddingProperties;
    }

    @Transactional(rollbackFor = Exception.class)
    public void synchronize(Article article) {
        if (article == null || article.getId() == null) {
            throw new IllegalArgumentException("Article id must not be null");
        }
        if (!isPublishedAndVisible(article)) {
            enqueueDeletion(article.getId());
            return;
        }

        KnowledgeDocumentEntity document = findOrCreateDocument(article);
        String contentHash = contentHash(article);
        KnowledgeDocumentVersionEntity existing = versionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentVersionEntity>()
                        .eq(KnowledgeDocumentVersionEntity::getDocumentId, document.getId())
                        .eq(KnowledgeDocumentVersionEntity::getContentHash, contentHash));
        if (existing != null) {
            enqueue(document.getId(), existing, IndexOperation.INDEX);
            return;
        }

        KnowledgeDocumentVersionEntity latest = versionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentVersionEntity>()
                        .eq(KnowledgeDocumentVersionEntity::getDocumentId, document.getId())
                        .orderByDesc(KnowledgeDocumentVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        KnowledgeDocumentVersionEntity version = newVersion(document.getId(), latest, article, contentHash);
        if (versionMapper.insert(version) != 1 || version.getId() == null) {
            throw new IllegalStateException("Unable to persist knowledge document version");
        }
        enqueue(document.getId(), version, IndexOperation.INDEX);
    }

    @Transactional(rollbackFor = Exception.class)
    public void enqueueDeletion(Long articleId) {
        if (articleId == null) {
            return;
        }
        KnowledgeDocumentEntity document = documentMapper.selectBySource(ARTICLE_SOURCE, articleId.toString());
        if (document == null) {
            return;
        }
        List<KnowledgeDocumentVersionEntity> versions = versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentVersionEntity>()
                        .eq(KnowledgeDocumentVersionEntity::getDocumentId, document.getId()));
        for (KnowledgeDocumentVersionEntity version : versions) {
            enqueue(document.getId(), version, IndexOperation.DELETE);
        }
    }

    private KnowledgeDocumentEntity findOrCreateDocument(Article article) {
        KnowledgeDocumentEntity existing = documentMapper.selectBySource(ARTICLE_SOURCE, article.getId().toString());
        if (existing != null) {
            return existing;
        }

        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
        document.setSourceType(ARTICLE_SOURCE);
        document.setSourceId(article.getId().toString());
        document.setSourceUri(sourceUri(article.getId()));
        if (documentMapper.insert(document) != 1 || document.getId() == null) {
            throw new IllegalStateException("Unable to persist knowledge document");
        }
        KnowledgeBaseDocumentEntity relation = new KnowledgeBaseDocumentEntity();
        relation.setKnowledgeBaseId(KnowledgeBaseIds.SYSTEM);
        relation.setDocumentId(document.getId());
        if (baseDocumentMapper.insert(relation) != 1) {
            throw new IllegalStateException("Unable to link document to system knowledge base");
        }
        return document;
    }

    private KnowledgeDocumentVersionEntity newVersion(Long documentId,
                                                        KnowledgeDocumentVersionEntity latest,
                                                        Article article,
                                                        String contentHash) {
        IndexProfile profile = indexProperties.indexProfile();
        IndexIdentity identity = embeddingProperties.indexIdentity(indexProperties.getChunkingPolicyVersion(),
                indexProperties.getKeywordSchemaVersion());
        KnowledgeDocumentVersionEntity version = new KnowledgeDocumentVersionEntity();
        version.setDocumentId(documentId);
        version.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        version.setContentHash(contentHash);
        version.setTitle(article.getTitle());
        version.setContent(article.getContent());
        version.setSourceUri(sourceUri(article.getId()));
        version.setStatus(DocumentVersionStatus.RECEIVED.name());
        version.setKeywordEnabled(profile.keywordEnabled() ? 1 : 0);
        version.setVectorEnabled(profile.vectorEnabled() ? 1 : 0);
        version.setKeywordAvailability(profile.keywordEnabled()
                ? IndexAvailabilityState.PENDING.name() : IndexAvailabilityState.NOT_REQUIRED.name());
        version.setVectorAvailability(profile.vectorEnabled()
                ? IndexAvailabilityState.PENDING.name() : IndexAvailabilityState.NOT_REQUIRED.name());
        version.setEmbeddingProvider(identity.embeddingProvider());
        version.setEmbeddingModel(identity.embeddingModel());
        version.setEmbeddingDimensions(identity.embeddingDimensions());
        version.setChunkingPolicyVersion(identity.chunkingPolicyVersion());
        version.setKeywordSchemaVersion(identity.keywordSchemaVersion());
        return version;
    }

    private void enqueue(Long documentId, KnowledgeDocumentVersionEntity version, IndexOperation operation) {
        IndexIdentity identity = new IndexIdentity(version.getEmbeddingProvider(), version.getEmbeddingModel(),
                version.getEmbeddingDimensions(), version.getChunkingPolicyVersion(), version.getKeywordSchemaVersion());
        String taskKey = "KNOWLEDGE_" + operation.name() + ":" + documentId + ":" + version.getId();
        taskGateway.enqueue(new IndexTask(taskKey, documentId.toString(), version.getId().toString(), operation,
                identity, Instant.now()));
    }

    private boolean isPublishedAndVisible(Article article) {
        return Objects.equals(String.valueOf(SystemConstants.ARTICLE_STATUS_NORMAL), article.getStatus())
                && (article.getDelFlag() == null || article.getDelFlag() == 0);
    }

    private String contentHash(Article article) {
        String material = article.getTitle() + "\u0000" + article.getSummary() + "\u0000" + article.getContent();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String sourceUri(Long articleId) {
        return "article:" + articleId;
    }
}
