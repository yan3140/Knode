package com.yan.knowledge.index;

import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.port.KeywordRetriever;
import com.yan.knowledge.core.port.ExactSignalExtractor;
import com.yan.knowledge.core.retrieval.ExactSignal;
import com.yan.knowledge.core.retrieval.RetrievalCandidate;
import com.yan.knowledge.core.retrieval.RetrievalQuery;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchKeywordRetriever implements KeywordRetriever {
    private final ElasticsearchKnowledgeRetriever delegate;
    private final ExactSignalExtractor exactSignalExtractor;

    public ElasticsearchKeywordRetriever(ElasticsearchKnowledgeRetriever delegate,
                                          ExactSignalExtractor exactSignalExtractor) {
        this.delegate = delegate;
        this.exactSignalExtractor = exactSignalExtractor;
    }

    @Override
    public List<RetrievalCandidate> retrieve(RetrievalQuery query, KnowledgeBaseScope scope, int candidateK) {
        List<ExactSignal> signals = query.requestedMode() == com.yan.knowledge.core.retrieval.RetrievalMode.EXACT
                ? exactSignalExtractor.extract(query.query()) : List.of();
        List<RetrievalCandidate> candidates = delegate.keyword(query, scope, candidateK);
        if (signals.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .map(candidate -> exactCandidate(candidate, signals))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private RetrievalCandidate exactCandidate(RetrievalCandidate candidate, List<ExactSignal> signals) {
        ExactSignal signal = signals.stream().filter(value -> contains(candidate, value.value())).findFirst().orElse(null);
        if (signal == null) {
            return null;
        }
        return new RetrievalCandidate(candidate.chunkId(), candidate.documentId(), candidate.documentVersionId(),
                candidate.content(), candidate.title(), candidate.section(), candidate.source(), candidate.keywordRank(),
                candidate.vectorRank(), true, signal.type(), candidate.vectorSimilarity(), candidate.keywordScore(),
                candidate.rrfScore(), candidate.finalScore());
    }

    private boolean contains(RetrievalCandidate candidate, String value) {
        String needle = value.toLowerCase(java.util.Locale.ROOT);
        return candidate.title().toLowerCase(java.util.Locale.ROOT).contains(needle)
                || candidate.section().toLowerCase(java.util.Locale.ROOT).contains(needle)
                || candidate.content().toLowerCase(java.util.Locale.ROOT).contains(needle);
    }
}
