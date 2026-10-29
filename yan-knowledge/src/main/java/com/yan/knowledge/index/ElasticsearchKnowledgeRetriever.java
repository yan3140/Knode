package com.yan.knowledge.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.retrieval.RetrievalCandidate;
import com.yan.knowledge.core.retrieval.RetrievalQuery;
import com.yan.knowledge.embedding.ZhipuEmbeddingProvider;
import com.yan.knowledge.domain.entity.KnowledgeDocumentEntity;
import com.yan.knowledge.mapper.KnowledgeDocumentMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchKnowledgeRetriever {
    private final ElasticsearchClient client; private final String indexName; private final double vectorCandidateSimilarity; private final ZhipuEmbeddingProvider embeddings; private final KnowledgeDocumentMapper documents;
    public ElasticsearchKnowledgeRetriever(ElasticsearchClient client, KnowledgeSearchProperties properties, ZhipuEmbeddingProvider embeddings, KnowledgeDocumentMapper documents) { this.client=client; this.indexName=properties.getIndexName(); this.vectorCandidateSimilarity=properties.getVectorCandidateSimilarity(); this.embeddings=embeddings; this.documents=documents; }
    public List<RetrievalCandidate> keyword(RetrievalQuery query, KnowledgeBaseScope scope, int candidateK) {
        try { return candidates(client.search(s -> s.index(indexName).size(candidateK).query(q -> q.bool(b -> b
                .filter(f -> f.terms(t -> t.field("documentId").terms(v -> v.value(scope.documentIds().stream().map(FieldValue::of).toList()))))
                .must(m -> m.multiMatch(mm -> mm.query(query.query()).fields("title^3", "section^2", "content"))))), KnowledgeSearchDocument.class), true); }
        catch (IOException e) { throw new IllegalStateException("Knowledge keyword search unavailable", e); }
    }
    public List<RetrievalCandidate> vector(RetrievalQuery query, KnowledgeBaseScope scope, int candidateK) {
        float[] raw=embeddings.embed(query.query()); List<Float> vector=new ArrayList<>(raw.length); for(float v:raw) vector.add(v);
        try { return candidates(client.search(s -> s.index(indexName).size(candidateK).knn(k -> k.field("contentVector").queryVector(vector).k(candidateK).numCandidates(candidateK * 2).similarity((float) vectorCandidateSimilarity)
                .filter(f -> f.terms(t -> t.field("documentId").terms(v -> v.value(scope.documentIds().stream().map(FieldValue::of).toList()))))), KnowledgeSearchDocument.class), false); }
        catch (IOException e) { throw new IllegalStateException("Knowledge vector search unavailable", e); }
    }
    private List<RetrievalCandidate> candidates(SearchResponse<KnowledgeSearchDocument> response, boolean keyword) {
        List<RetrievalCandidate> result=new ArrayList<>(); int rank=1; for(Hit<KnowledgeSearchDocument> hit:response.hits().hits()) { KnowledgeSearchDocument d=hit.source(); if(d!=null && current(d)) result.add(new RetrievalCandidate(d.chunkId(),d.documentId().toString(),d.documentVersionId().toString(),d.content(),d.title(),d.section(),d.sourceUri(),keyword?rank:null,keyword?null:rank,false,null,keyword?null:rawCosineSimilarity(hit.score()),keyword?hit.score():null,null,null)); rank++; } return List.copyOf(result);
    }
    static double rawCosineSimilarity(double elasticsearchScore) { return Math.max(-1.0, Math.min(1.0, (2.0 * elasticsearchScore) - 1.0)); }
    private boolean current(KnowledgeSearchDocument document) { KnowledgeDocumentEntity current=documents.selectById(document.documentId()); return current != null && document.documentVersionId().equals(current.getCurrentVersionId()); }
}
