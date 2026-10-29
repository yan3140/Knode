package com.yan.knowledge.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ElasticsearchKnowledgeRetrieverTest {

    @Test
    void convertsElasticsearchCosineScoreBackToRawSimilarity() {
        assertThat(ElasticsearchKnowledgeRetriever.rawCosineSimilarity(0.85)).isCloseTo(0.70, within(1.0e-9));
        assertThat(ElasticsearchKnowledgeRetriever.rawCosineSimilarity(0.50)).isZero();
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
