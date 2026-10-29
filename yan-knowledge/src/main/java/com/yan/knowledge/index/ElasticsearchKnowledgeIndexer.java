package com.yan.knowledge.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.yan.knowledge.core.domain.IndexTask;
import com.yan.knowledge.core.domain.KnowledgeChunk;
import com.yan.knowledge.core.domain.KnowledgeDocumentVersion;
import com.yan.knowledge.core.port.KnowledgeIndexer;
import com.yan.knowledge.embedding.KnowledgeEmbeddingProperties;
import com.yan.knowledge.embedding.ZhipuEmbeddingProvider;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchKnowledgeIndexer implements KnowledgeIndexer {

    private final ElasticsearchClient client;
    private final String indexName;
    private final ZhipuEmbeddingProvider embeddingProvider;
    private final KnowledgeEmbeddingProperties embeddingProperties;
    private final AtomicBoolean indexReady = new AtomicBoolean();

    public ElasticsearchKnowledgeIndexer(ElasticsearchClient client, KnowledgeSearchProperties properties,
                                         ZhipuEmbeddingProvider embeddingProvider,
                                         KnowledgeEmbeddingProperties embeddingProperties) {
        this.client = client;
        this.indexName = properties.getIndexName();
        this.embeddingProvider = embeddingProvider;
        this.embeddingProperties = embeddingProperties;
    }

    @Override
    public void index(KnowledgeDocumentVersion version, List<KnowledgeChunk> chunks, IndexTask task) {
        try {
            ensureIndex();
            BulkRequest.Builder request = new BulkRequest.Builder().refresh(Refresh.WaitFor);
            chunks.forEach(chunk -> request.operations(operation -> operation.index(index -> index
                    .index(indexName)
                    .id(chunk.id())
                    .document(toDocument(version, chunk)))));
            BulkResponse response = client.bulk(request.build());
            if (response.errors()) {
                throw new IllegalStateException("Knowledge index bulk write contains failed chunks");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write knowledge chunks to Elasticsearch", exception);
        }
    }

    @Override
    public void delete(KnowledgeDocumentVersion version, IndexTask task) {
        try {
            ensureIndex();
            client.deleteByQuery(request -> request
                    .index(indexName)
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
                    .query(query -> query.term(term -> term.field("documentVersionId")
                            .value(version.id()))));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to delete knowledge chunks from Elasticsearch", exception);
        }
    }

    private synchronized void ensureIndex() throws IOException {
        if (indexReady.get()) {
            return;
        }
        if (client.indices().exists(ExistsRequest.of(request -> request.index(indexName))).value()) {
            indexReady.set(true);
            return;
        }
        try {
            client.indices().create(indexDefinition());
        } catch (ElasticsearchException exception) {
            if (exception.status() != 400 || !"resource_already_exists_exception".equals(exception.error().type())) {
                throw exception;
            }
        }
        indexReady.set(true);
    }

    private CreateIndexRequest indexDefinition() {
        return new CreateIndexRequest.Builder()
                .index(indexName)
                .settings(settings -> settings.numberOfShards("1").numberOfReplicas("0"))
                .mappings(mapping -> mapping
                        .properties("chunkId", property -> property.keyword(keyword -> keyword.index(false)))
                        .properties("documentId", property -> property.long_(number -> number))
                        .properties("documentVersionId", property -> property.long_(number -> number))
                        .properties("sourceType", property -> property.keyword(keyword -> keyword))
                        .properties("sourceUri", property -> property.keyword(keyword -> keyword.index(false)))
                        .properties("title", this::textProperty)
                        .properties("section", this::textProperty)
                        .properties("content", this::textProperty)
                        .properties("articleId", property -> property.long_(number -> number))
                        .properties("visibility", property -> property.keyword(keyword -> keyword))
                        .properties("embeddingProvider", property -> property.keyword(keyword -> keyword))
                        .properties("embeddingModel", property -> property.keyword(keyword -> keyword))
                        .properties("embeddingDimensions", property -> property.integer(number -> number))
                        .properties("chunkingPolicyVersion", property -> property.keyword(keyword -> keyword))
                        .properties("keywordSchemaVersion", property -> property.keyword(keyword -> keyword))
                        .properties("contentVector", property -> property.denseVector(vector -> vector
                                .dims(1024).index(true).similarity(DenseVectorSimilarity.Cosine))))
                .build();
    }

    private co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.mapping.Property>
    textProperty(co.elastic.clients.elasticsearch._types.mapping.Property.Builder property) {
        return property.text(text -> text.analyzer("smartcn").searchAnalyzer("smartcn"));
    }

    private KnowledgeSearchDocument toDocument(KnowledgeDocumentVersion version, KnowledgeChunk chunk) {
        String sourceType = chunk.metadata().getOrDefault("sourceType", "USER_DOCUMENT");
        Long articleId = articleIdFor(sourceType, chunk.metadata().get("articleId"));
        return new KnowledgeSearchDocument(chunk.id(), Long.valueOf(chunk.documentId()),
                Long.valueOf(chunk.documentVersionId()), sourceType, chunk.source(), chunk.title(), chunk.section(),
                chunk.content(), articleId,
                "ARTICLE".equals(sourceType) ? "PUBLIC" : "PRIVATE", version.indexIdentity().embeddingProvider(),
                version.indexIdentity().embeddingModel(), version.indexIdentity().embeddingDimensions(),
                version.indexIdentity().chunkingPolicyVersion(), version.indexIdentity().keywordSchemaVersion(), vectorFor(version, chunk));
    }

    static Long articleIdFor(String sourceType, String sourceId) {
        return "ARTICLE".equals(sourceType) ? Long.valueOf(sourceId) : null;
    }

    private List<Float> vectorFor(KnowledgeDocumentVersion version, KnowledgeChunk chunk) {
        if (!version.indexProfile().vectorEnabled() || embeddingProperties.getApiKey() == null || embeddingProperties.getApiKey().isBlank()) {
            return null;
        }
        float[] values = embeddingProvider.embed(chunk.content());
        List<Float> vector = new java.util.ArrayList<>(values.length);
        for (float value : values) vector.add(value);
        return List.copyOf(vector);
    }
}
