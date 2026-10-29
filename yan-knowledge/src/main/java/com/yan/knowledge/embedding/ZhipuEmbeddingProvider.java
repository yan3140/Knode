package com.yan.knowledge.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yan.knowledge.core.domain.IndexIdentity;
import com.yan.knowledge.core.port.EmbeddingProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ZhipuEmbeddingProvider implements EmbeddingProvider {
    private final KnowledgeEmbeddingProperties properties;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public ZhipuEmbeddingProvider(KnowledgeEmbeddingProperties properties) { this.properties = properties; }

    @Override public float[] embed(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding text must not be blank");
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) throw new IllegalStateException("ZHIPU_KEY is not configured");
        try {
            Map<String, Object> body = new LinkedHashMap<>(); body.put("model", properties.getModel()); body.put("input", text); body.put("dimensions", properties.getDimensions());
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint())).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + properties.getApiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Zhipu embedding request failed with status " + response.statusCode());
            JsonNode embedding = mapper.readTree(response.body()).path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.size() != properties.getDimensions()) throw new IllegalStateException("Zhipu embedding dimensions do not match the frozen index identity");
            float[] vector = new float[embedding.size()]; for (int i = 0; i < vector.length; i++) vector[i] = (float) embedding.get(i).asDouble(); return vector;
        } catch (Exception e) { if (e instanceof IllegalStateException state) throw state; throw new IllegalStateException("Unable to call Zhipu embedding API", e); }
    }
    @Override public IndexIdentity indexIdentity() { return properties.indexIdentity("runtime", "runtime"); }
    private String endpoint() { String base = properties.getBaseUrl(); return base.endsWith("/") ? base + "embeddings" : base + "/embeddings"; }
}
