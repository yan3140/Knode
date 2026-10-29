package com.yan.knowledge.index;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "blog.knowledge.search")
public class KnowledgeSearchProperties {

    private String indexName = "yan_knowledge_chunks_v1";
    private double vectorCandidateSimilarity = 0.55;
    private double vectorEvidenceSimilarity = 0.65;
    private double vectorStrongSimilarity = 0.72;

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public double getVectorCandidateSimilarity() {
        return vectorCandidateSimilarity;
    }

    public void setVectorCandidateSimilarity(double vectorCandidateSimilarity) {
        this.vectorCandidateSimilarity = vectorCandidateSimilarity;
    }

    public double getVectorEvidenceSimilarity() {
        return vectorEvidenceSimilarity;
    }

    public void setVectorEvidenceSimilarity(double vectorEvidenceSimilarity) {
        this.vectorEvidenceSimilarity = vectorEvidenceSimilarity;
    }

    public double getVectorStrongSimilarity() {
        return vectorStrongSimilarity;
    }

    public void setVectorStrongSimilarity(double vectorStrongSimilarity) {
        this.vectorStrongSimilarity = vectorStrongSimilarity;
    }
}
