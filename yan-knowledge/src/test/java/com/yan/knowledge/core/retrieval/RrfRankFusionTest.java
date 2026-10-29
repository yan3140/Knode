package com.yan.knowledge.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfRankFusionTest {

    @Test
    void shouldFuseRanksWithoutAddingHeterogeneousRawScores() {
        RetrievalCandidate keywordOnly = candidate("chunk-a", "doc-a", 0.10, ExactMatchType.IDENTIFIER);
        RetrievalCandidate vectorOnly = candidate("chunk-b", "doc-b", 0.95, null);

        List<RetrievalCandidate> result = RrfRankFusion.fuse(
                List.of(keywordOnly),
                List.of(vectorOnly),
                new RrfRankFusion.Config(1.0, 1.0, 60, 10, 10));

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(item -> assertThat(item.rrfScore()).isEqualTo(1.0 / 61.0));
        assertThat(result.get(0).exactMatch()).isTrue();
        assertThat(result.get(0).keywordRank()).isEqualTo(1);
        assertThat(result.get(1).vectorRank()).isEqualTo(1);
        assertThat(result.get(0).finalScore()).isEqualTo(result.get(0).rrfScore());
    }

    @Test
    void shouldMergeSameChunkAndLimitChunksPerDocument() {
        RetrievalCandidate first = candidate("chunk-1", "doc-1", 0.4, null);
        RetrievalCandidate second = candidate("chunk-2", "doc-1", 0.3, null);
        RetrievalCandidate shared = candidate("chunk-shared", "doc-1", 0.8, null);

        List<RetrievalCandidate> result = RrfRankFusion.fuse(
                List.of(first, shared),
                List.of(shared, second),
                new RrfRankFusion.Config(1.0, 1.0, 1, 10, 2));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(RetrievalCandidate::chunkId)
                .containsExactly("chunk-shared", "chunk-1");
        assertThat(result.get(0).keywordRank()).isEqualTo(2);
        assertThat(result.get(0).vectorRank()).isEqualTo(1);
    }

    private RetrievalCandidate candidate(String chunkId, String documentId,
                                         double vectorSimilarity, ExactMatchType exactType) {
        return new RetrievalCandidate(
                chunkId,
                documentId,
                "version-1",
                "content-" + chunkId,
                "title",
                "section",
                "source",
                null,
                null,
                exactType != null,
                exactType,
                vectorSimilarity,
                0.1,
                null,
                null);
    }
}
