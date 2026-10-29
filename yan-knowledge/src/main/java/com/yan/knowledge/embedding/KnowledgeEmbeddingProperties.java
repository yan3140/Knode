package com.yan.knowledge.embedding;

import com.yan.knowledge.core.domain.IndexIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Dedicated configuration for embeddings; it never shares the Agent model key. */
@Component
@ConfigurationProperties(prefix = "blog.knowledge.embedding")
public class KnowledgeEmbeddingProperties {

    private String provider = "zhipu-openai-compatible";
    private String apiKey = "";
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4/";
    private String model = "embedding-3";
    private int dimensions = 1024;

    public IndexIdentity indexIdentity(String chunkingPolicyVersion, String keywordSchemaVersion) {
        return new IndexIdentity(provider, model, dimensions, chunkingPolicyVersion, keywordSchemaVersion);
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }
}
