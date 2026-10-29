package com.yan.knowledge.core.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只按排名融合异构召回源，避免把关键词分数和向量相似度直接相加。
 * 同一 Chunk 只保留一份，并在最终排序后限制单文档切片数量。
 */
public final class RrfRankFusion {

    private RrfRankFusion() {
    }

    public static List<RetrievalCandidate> fuse(List<RetrievalCandidate> keywordCandidates,
                                                 List<RetrievalCandidate> vectorCandidates,
                                                 Config config) {
        Objects.requireNonNull(keywordCandidates, "keywordCandidates must not be null");
        Objects.requireNonNull(vectorCandidates, "vectorCandidates must not be null");
        Objects.requireNonNull(config, "config must not be null");

        Map<String, AccumulatedCandidate> accumulated = new LinkedHashMap<>();
        addRanks(accumulated, keywordCandidates, true);
        addRanks(accumulated, vectorCandidates, false);

        List<RetrievalCandidate> ranked = accumulated.values().stream()
                .map(item -> item.toCandidate(config))
                .sorted(Comparator.comparing(RetrievalCandidate::finalScore, Comparator.reverseOrder())
                        .thenComparing(RetrievalCandidate::documentVersionId)
                        .thenComparing(RetrievalCandidate::chunkId))
                .toList();

        List<RetrievalCandidate> result = new ArrayList<>();
        Map<String, Integer> documentCounts = new HashMap<>();
        for (RetrievalCandidate candidate : ranked) {
            int count = documentCounts.getOrDefault(candidate.documentId(), 0);
            if (count >= config.maxChunksPerDocument()) {
                continue;
            }
            result.add(candidate);
            documentCounts.put(candidate.documentId(), count + 1);
            if (result.size() == config.finalTopK()) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static void addRanks(Map<String, AccumulatedCandidate> accumulated,
                                 List<RetrievalCandidate> candidates,
                                 boolean keywordSource) {
        for (int index = 0; index < candidates.size(); index++) {
            RetrievalCandidate candidate = Objects.requireNonNull(candidates.get(index), "candidate must not be null");
            int rank = index + 1;
            AccumulatedCandidate item = accumulated.computeIfAbsent(
                    candidate.chunkId(), ignored -> new AccumulatedCandidate(candidate));
            item.merge(candidate, rank, keywordSource);
        }
    }

    public record Config(
            double keywordWeight,
            double vectorWeight,
            int rrfRankConstant,
            int finalTopK,
            int maxChunksPerDocument
    ) {
        public Config {
            if (!Double.isFinite(keywordWeight) || keywordWeight < 0
                    || !Double.isFinite(vectorWeight) || vectorWeight < 0
                    || keywordWeight == 0 && vectorWeight == 0) {
                throw new IllegalArgumentException("RRF weights must be finite and at least one must be positive");
            }
            if (rrfRankConstant < 0 || finalTopK < 1 || maxChunksPerDocument < 1) {
                throw new IllegalArgumentException("RRF configuration must be non-negative or positive as appropriate");
            }
        }
    }

    private static final class AccumulatedCandidate {
        private RetrievalCandidate base;
        private Integer keywordRank;
        private Integer vectorRank;
        private boolean exactMatch;
        private ExactMatchType exactType;
        private Double vectorSimilarity;
        private Double keywordScore;

        private AccumulatedCandidate(RetrievalCandidate base) {
            this.base = base;
            this.exactMatch = base.exactMatch();
            this.exactType = base.exactType();
            this.vectorSimilarity = base.vectorSimilarity();
            this.keywordScore = base.keywordScore();
        }

        private void merge(RetrievalCandidate candidate, int rank, boolean keywordSource) {
            if (keywordSource) {
                keywordRank = keywordRank == null ? rank : Math.min(keywordRank, rank);
                keywordScore = preferValue(keywordScore, candidate.keywordScore());
            } else {
                vectorRank = vectorRank == null ? rank : Math.min(vectorRank, rank);
                vectorSimilarity = preferValue(vectorSimilarity, candidate.vectorSimilarity());
            }
            if (!exactMatch && candidate.exactMatch()) {
                exactMatch = true;
                exactType = candidate.exactType();
            }
            if (base.content().isBlank() && !candidate.content().isBlank()) {
                base = candidate;
            }
        }

        private RetrievalCandidate toCandidate(Config config) {
            double score = 0;
            if (keywordRank != null) {
                score += config.keywordWeight() / (keywordRank + config.rrfRankConstant());
            }
            if (vectorRank != null) {
                score += config.vectorWeight() / (vectorRank + config.rrfRankConstant());
            }
            return base.withFusion(keywordRank, vectorRank, exactMatch, exactType,
                    vectorSimilarity, keywordScore, score);
        }

        private Double preferValue(Double current, Double incoming) {
            return current != null ? current : incoming;
        }
    }
}
