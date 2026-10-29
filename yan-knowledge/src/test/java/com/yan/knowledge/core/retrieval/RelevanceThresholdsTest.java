package com.yan.knowledge.core.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RelevanceThresholdsTest {

    private final RelevanceThresholds thresholds = new RelevanceThresholds(0.55, 0.65, 0.72);

    @Test
    void admitsOnlyEvidenceAtOrAboveTheConfiguredThreshold() {
        assertThat(thresholds.admitsEvidence(candidate(0.65, false))).isTrue();
        assertThat(thresholds.admitsEvidence(candidate(0.6499, false))).isFalse();
    }

    @Test
    void preservesExactKeywordEvidenceWithoutVectorSimilarity() {
        assertThat(thresholds.admitsEvidence(candidate(null, true))).isTrue();
    }

    @Test
    void rejectsThresholdsInTheWrongOrder() {
        assertThatThrownBy(() -> new RelevanceThresholds(0.70, 0.65, 0.72))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RetrievalCandidate candidate(Double similarity, boolean exact) {
        return new RetrievalCandidate(
                "chunk", "document", "version", "content", "title", "section", "source",
                exact ? 1 : null, similarity == null ? null : 1, exact,
                exact ? ExactMatchType.IDENTIFIER : null, similarity, exact ? 1.0 : null, null, null);
    }
}
