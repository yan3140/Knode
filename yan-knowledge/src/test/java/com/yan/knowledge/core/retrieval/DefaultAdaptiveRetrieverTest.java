package com.yan.knowledge.core.retrieval;

import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.domain.KnowledgeChunk;
import com.yan.knowledge.core.port.ExactSignalExtractor;
import com.yan.knowledge.core.port.FullContextSource;
import com.yan.knowledge.core.port.KeywordRetriever;
import com.yan.knowledge.core.port.KnowledgeScopeResolver;
import com.yan.knowledge.core.port.TokenCounter;
import com.yan.knowledge.core.port.VectorRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAdaptiveRetrieverTest {

    @Test
    void shouldUseHybridForAutoEvenWhenExactSignalExists() {
        RetrievalCandidate candidate = candidate("exact", true);
        FakeKeywordRetriever keyword = new FakeKeywordRetriever(List.of(candidate));
        FakeVectorRetriever vector = new FakeVectorRetriever(List.of());

        RetrievalResult result = retriever(keyword, vector, query(RetrievalMode.AUTO), List.of(), true)
                .retrieve(query(RetrievalMode.AUTO));

        assertThat(result.selectedMode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.citations()).extracting(citation -> citation.chunkId())
                .containsExactly("exact");
        assertThat(vector.called).isTrue();
    }

    @Test
    void shouldLimitRankedRetrievalResultsToTopK() {
        FakeKeywordRetriever keyword = new FakeKeywordRetriever(List.of(
                candidate("one", true),
                candidate("two", true),
                candidate("three", true),
                candidate("four", true),
                candidate("five", true),
                candidate("six", true)));

        RetrievalResult result = retriever(
                keyword,
                new FakeVectorRetriever(List.of()),
                query(RetrievalMode.EXACT),
                List.of(),
                true).retrieve(query(RetrievalMode.EXACT));

        assertThat(result.citations()).extracting(citation -> citation.chunkId())
                .containsExactly("one", "two", "three", "four", "five");
    }

    @Test
    void shouldNotSwitchAutoToFullContextForSmallScopes() {
        KnowledgeChunk chunk = new KnowledgeChunk(
                "chunk-1", "doc-1", "version-1", 0, "short context", "title", "section", "source", Map.of());

        RetrievalResult result = retriever(
                new FakeKeywordRetriever(List.of()),
                new FakeVectorRetriever(List.of()),
                query(RetrievalMode.AUTO),
                List.of(chunk),
                false).retrieve(query(RetrievalMode.AUTO));

        assertThat(result.selectedMode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void shouldDegradeToKeywordWhenVectorRetrieverFails() {
        FakeKeywordRetriever keyword = new FakeKeywordRetriever(List.of(candidate("keyword", false)));
        FakeVectorRetriever vector = new FakeVectorRetriever(List.of());
        vector.failure = new IllegalStateException("vector unavailable");

        RetrievalResult result = retriever(keyword, vector, query(RetrievalMode.HYBRID), List.of(), false)
                .retrieve(query(RetrievalMode.HYBRID));

        assertThat(result.selectedMode()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons()).contains(DegradationReason.VECTOR_UNAVAILABLE);
    }

    @Test
    void shouldKeepCandidatesAlreadyAdmittedByVectorRetriever() {
        RetrievalCandidate weak = new RetrievalCandidate(
                "weak", "doc-1", "version-1", "content", "title", "section", "source",
                null, 1, false, null, 0.6499, null, null, null);

        RetrievalResult result = retriever(
                new FakeKeywordRetriever(List.of()),
                new FakeVectorRetriever(List.of(weak)),
                query(RetrievalMode.VECTOR),
                List.of(),
                false).retrieve(query(RetrievalMode.VECTOR));

        assertThat(result.citations()).extracting(citation -> citation.chunkId()).containsExactly("weak");
        assertThat(result.degradationReasons()).isEmpty();
    }

    @Test
    void shouldKeepKeywordOnlyEvidenceAndNotCapChunksPerDocument() {
        List<RetrievalCandidate> candidates = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> new RetrievalCandidate("keyword-" + i, "doc-1", "version-1",
                        "answer", "title", "section", "source", i + 1, null,
                        false, null, null, 10.0 - i, null, null)).toList();
        RetrievalResult result = retriever(new FakeKeywordRetriever(candidates),
                new FakeVectorRetriever(List.of()), query(RetrievalMode.HYBRID), List.of(), false)
                .retrieve(query(RetrievalMode.HYBRID));
        assertThat(result.citations()).hasSize(5);
        assertThat(result.citations()).allSatisfy(citation -> assertThat(citation.vectorSimilarity()).isNull());
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void shouldStartBothSourcesBeforeWaitingForEither() {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var started = new java.util.concurrent.CountDownLatch(2);
        java.util.function.Supplier<List<RetrievalCandidate>> work = () -> {
            started.countDown();
            try {
                if (!started.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("sources were executed sequentially");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return List.of(candidate("same-chunk", false));
        };
        try {
            var retriever = new DefaultAdaptiveRetriever(scope -> List.of(),
                    (q, scope, k) -> work.get(), (q, scope, k) -> work.get(),
                    q -> new KnowledgeBaseScope("kb-1", java.util.Set.of("doc-1")),
                    String::length, text -> List.of(), new RrfRankFusion.Config(1, 1, 60, 5, 2),
                    new RelevanceThresholds(0.55, 0.65, 0.72), executor);
            var result = retriever.retrieve(query(RetrievalMode.HYBRID));
            assertThat(result.degraded()).isFalse();
            assertThat(result.citations()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private DefaultAdaptiveRetriever retriever(FakeKeywordRetriever keyword,
                                               FakeVectorRetriever vector,
                                               RetrievalQuery query,
                                               List<KnowledgeChunk> chunks,
                                               boolean exactSignal) {
        FullContextSource contextSource = scope -> chunks;
        KnowledgeScopeResolver scopeResolver = ignored -> new KnowledgeBaseScope(query.knowledgeBaseId(), List.of("doc-1").stream().collect(java.util.stream.Collectors.toSet()));
        TokenCounter tokenCounter = String::length;
        ExactSignalExtractor signals = ignored -> exactSignal
                ? List.of(new ExactSignal(ExactMatchType.IDENTIFIER, "pool"))
                : List.of();
        return new DefaultAdaptiveRetriever(
                contextSource,
                keyword,
                vector,
                scopeResolver,
                tokenCounter,
                signals,
                new RrfRankFusion.Config(1.0, 1.0, 60, 5, 2),
                new RelevanceThresholds(0.55, 0.65, 0.72), Runnable::run);
    }

    private RetrievalQuery query(RetrievalMode mode) {
        return new RetrievalQuery(
                "kb-1", "线程池", Map.of(), Map.of(), mode, 5, 10,
                new ContextBudget(1000, 100, 100), "trace-1");
    }

    private RetrievalCandidate candidate(String id, boolean exact) {
        return new RetrievalCandidate(
                id, "doc-1", "version-1", "content", "title", "section", "source",
                null, null, exact, exact ? ExactMatchType.IDENTIFIER : null,
                0.8, 0.7, null, null);
    }

    private static final class FakeKeywordRetriever implements KeywordRetriever {
        private final List<RetrievalCandidate> candidates;

        private FakeKeywordRetriever(List<RetrievalCandidate> candidates) {
            this.candidates = candidates;
        }

        @Override
        public List<RetrievalCandidate> retrieve(RetrievalQuery query, KnowledgeBaseScope scope, int candidateK) {
            return candidates;
        }
    }

    private static final class FakeVectorRetriever implements VectorRetriever {
        private final List<RetrievalCandidate> candidates;
        private RuntimeException failure;
        private boolean called;

        private FakeVectorRetriever(List<RetrievalCandidate> candidates) {
            this.candidates = candidates;
        }

        @Override
        public List<RetrievalCandidate> retrieve(RetrievalQuery query, KnowledgeBaseScope scope, int candidateK) {
            called = true;
            if (failure != null) {
                throw failure;
            }
            return candidates;
        }
    }
}
