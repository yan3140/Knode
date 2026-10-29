package com.yan.knowledge.core.retrieval;

/** Raw cosine thresholds used at candidate retrieval and final evidence admission. */
public record RelevanceThresholds(
        double candidateMinSimilarity,
        double evidenceMinSimilarity,
        double strongMinSimilarity
) {
    public RelevanceThresholds {
        validateCosine(candidateMinSimilarity, "candidateMinSimilarity");
        validateCosine(evidenceMinSimilarity, "evidenceMinSimilarity");
        validateCosine(strongMinSimilarity, "strongMinSimilarity");
        if (candidateMinSimilarity > evidenceMinSimilarity
                || evidenceMinSimilarity > strongMinSimilarity) {
            throw new IllegalArgumentException(
                    "similarity thresholds must satisfy candidate <= evidence <= strong");
        }
    }

    public boolean admitsEvidence(RetrievalCandidate candidate) {
        if (candidate.exactMatch()) {
            return true;
        }
        return candidate.vectorSimilarity() != null
                && candidate.vectorSimilarity() >= evidenceMinSimilarity;
    }

    private static void validateCosine(double value, String name) {
        if (!Double.isFinite(value) || value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be a finite cosine value between -1 and 1");
        }
    }
}
