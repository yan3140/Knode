package com.yan.knowledge.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.port.ExactSignalExtractor;
import com.yan.knowledge.core.retrieval.ExactMatchType;
import com.yan.knowledge.core.retrieval.ExactSignal;
import com.yan.knowledge.core.retrieval.RetrievalCandidate;
import com.yan.knowledge.core.retrieval.RetrievalMode;
import com.yan.knowledge.core.retrieval.RetrievalQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElasticsearchKeywordRetrieverTest {

    @Test
    void keepsOnlyExactCandidatesAndMarksTheirCitationFields() {
        ElasticsearchKnowledgeRetriever delegate = mock(ElasticsearchKnowledgeRetriever.class);
        ExactSignalExtractor signals = mock(ExactSignalExtractor.class);
        RetrievalCandidate exact = candidate("错误码 KNODE-E2E-409");
        RetrievalCandidate other = candidate("普通说明");
        when(delegate.keyword(any(), any(), any(Integer.class))).thenReturn(List.of(exact, other));
        when(signals.extract("KNODE-E2E-409"))
                .thenReturn(List.of(new ExactSignal(ExactMatchType.IDENTIFIER, "KNODE-E2E-409")));

        List<RetrievalCandidate> result = new ElasticsearchKeywordRetriever(delegate, signals)
                .retrieve(query(), new KnowledgeBaseScope("2", java.util.Set.of("1")), 5);

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.exactMatch()).isTrue();
            assertThat(candidate.exactType()).isEqualTo(ExactMatchType.IDENTIFIER);
        });
    }

    @Test
    void doesNotMatchAnOlderErrorCodeByItsPrefix() {
        ElasticsearchKnowledgeRetriever delegate = mock(ElasticsearchKnowledgeRetriever.class);
        ExactSignalExtractor signals = mock(ExactSignalExtractor.class);
        when(delegate.keyword(any(), any(), any(Integer.class))).thenReturn(List.of(candidate("错误码 KNODE-E2E-410")));
        when(signals.extract("KNODE-E2E-409"))
                .thenReturn(List.of(new ExactSignal(ExactMatchType.ERROR_CODE, "KNODE-E2E-409")));

        List<RetrievalCandidate> result = new ElasticsearchKeywordRetriever(delegate, signals)
                .retrieve(query(), new KnowledgeBaseScope("2", java.util.Set.of("1")), 5);

        assertThat(result).isEmpty();
    }

    private RetrievalQuery query() {
        return new RetrievalQuery("2", "KNODE-E2E-409", java.util.Map.of(), java.util.Map.of(), RetrievalMode.EXACT,
                5, 20, new com.yan.knowledge.core.retrieval.ContextBudget(4_000, 0, 0), "trace");
    }

    private RetrievalCandidate candidate(String content) {
        return new RetrievalCandidate("chunk", "1", "1", content, "title", "section", "source",
                1, null, false, null, null, 1.0, null, null);
    }
}
