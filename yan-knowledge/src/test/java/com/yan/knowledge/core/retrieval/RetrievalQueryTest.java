package com.yan.knowledge.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalQueryTest {

    @Test
    void shouldCopyContextMapsAndExposeAvailableTokenBudget() {
        RetrievalQuery query = new RetrievalQuery(
                "kb-1",
                "线程池拒绝策略",
                Map.of("service", "order"),
                Map.of("visibility", "PUBLIC"),
                RetrievalMode.AUTO,
                5,
                20,
                new ContextBudget(4000, 1000, 200),
                "trace-1");

        assertThat(query.queryContext()).containsEntry("service", "order");
        assertThat(query.filters()).containsEntry("visibility", "PUBLIC");
        assertThat(query.contextBudget().availableInputTokens()).isEqualTo(2800);
    }

    @Test
    void shouldRejectCandidateKSmallerThanTopK() {
        assertThatThrownBy(() -> new RetrievalQuery(
                "kb-1", "query", Map.of(), Map.of(), RetrievalMode.AUTO,
                10, 5, new ContextBudget(100, 10, 10), "trace-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidateK");
    }
}
