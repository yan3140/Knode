package com.yan.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.yan.knowledge.core.domain.IndexIdentity;
import org.junit.jupiter.api.Test;

class KnowledgeEmbeddingPropertiesTest {

    @Test
    void buildsVersionIdentityFromEmbeddingConfigurationOnly() {
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties();
        properties.setProvider("zhipu-openai-compatible");
        properties.setModel("embedding-3");
        properties.setDimensions(1024);

        IndexIdentity identity = properties.indexIdentity("markdown-heading-v1", "elasticsearch-standard-v1");

        assertThat(identity.embeddingProvider()).isEqualTo("zhipu-openai-compatible");
        assertThat(identity.embeddingModel()).isEqualTo("embedding-3");
        assertThat(identity.embeddingDimensions()).isEqualTo(1024);
        assertThat(identity.chunkingPolicyVersion()).isEqualTo("markdown-heading-v1");
        assertThat(identity.keywordSchemaVersion()).isEqualTo("elasticsearch-standard-v1");
    }
}
