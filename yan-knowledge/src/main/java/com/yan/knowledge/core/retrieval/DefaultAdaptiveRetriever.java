package com.yan.knowledge.core.retrieval;

import com.yan.knowledge.core.domain.KnowledgeBaseScope;
import com.yan.knowledge.core.domain.KnowledgeChunk;
import com.yan.knowledge.core.domain.KnowledgeCitation;
import com.yan.knowledge.core.port.ExactSignalExtractor;
import com.yan.knowledge.core.port.FullContextSource;
import com.yan.knowledge.core.port.KeywordRetriever;
import com.yan.knowledge.core.port.KnowledgeScopeResolver;
import com.yan.knowledge.core.port.TokenCounter;
import com.yan.knowledge.core.port.VectorRetriever;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 公共自适应路由实现：先使用 Adapter 解析范围和精确信号，再选择全文、精确或混合检索。
 * 具体数据库、搜索客户端和模型调用仍然隐藏在 port 的 Adapter 后面。
 */
public final class DefaultAdaptiveRetriever implements AdaptiveRetriever {

    private final FullContextSource fullContextSource;
    private final KeywordRetriever keywordRetriever;
    private final VectorRetriever vectorRetriever;
    private final KnowledgeScopeResolver scopeResolver;
    private final TokenCounter tokenCounter;
    private final ExactSignalExtractor exactSignalExtractor;
    private final RrfRankFusion.Config rrfConfig;
    private final RelevanceThresholds relevanceThresholds;
    private final Executor retrievalExecutor;

    public DefaultAdaptiveRetriever(FullContextSource fullContextSource,
                                    KeywordRetriever keywordRetriever,
                                    VectorRetriever vectorRetriever,
                                    KnowledgeScopeResolver scopeResolver,
                                    TokenCounter tokenCounter,
                                    ExactSignalExtractor exactSignalExtractor,
                                    RrfRankFusion.Config rrfConfig,
                                    RelevanceThresholds relevanceThresholds,
                                    Executor retrievalExecutor) {
        this.fullContextSource = Objects.requireNonNull(fullContextSource, "fullContextSource must not be null");
        this.keywordRetriever = Objects.requireNonNull(keywordRetriever, "keywordRetriever must not be null");
        this.vectorRetriever = Objects.requireNonNull(vectorRetriever, "vectorRetriever must not be null");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver must not be null");
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
        this.exactSignalExtractor = Objects.requireNonNull(exactSignalExtractor, "exactSignalExtractor must not be null");
        this.rrfConfig = Objects.requireNonNull(rrfConfig, "rrfConfig must not be null");
        this.relevanceThresholds = Objects.requireNonNull(relevanceThresholds, "relevanceThresholds must not be null");
        this.retrievalExecutor = Objects.requireNonNull(retrievalExecutor, "retrievalExecutor must not be null");
    }

    @Override
    public RetrievalResult retrieve(RetrievalQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        long startedAt = System.nanoTime();
        KnowledgeBaseScope scope = Objects.requireNonNull(scopeResolver.resolve(query), "resolved scope must not be null");
        List<DegradationReason> reasons = new ArrayList<>();

        RetrievalMode requestedMode = query.requestedMode();
        if (requestedMode == RetrievalMode.FULL_CONTEXT) {
            return fullContextOrFallback(query, scope, reasons, startedAt);
        }
        if (requestedMode == RetrievalMode.EXACT) {
            return keywordResult(query, scope, RetrievalMode.EXACT, reasons, startedAt);
        }
        if (requestedMode == RetrievalMode.KEYWORD) {
            return keywordResult(query, scope, RetrievalMode.KEYWORD, reasons, startedAt);
        }
        if (requestedMode == RetrievalMode.VECTOR) {
            return vectorResult(query, scope, reasons, startedAt);
        }
        return hybridResult(query, scope, reasons, startedAt);
    }

    private RetrievalResult fullContextOrFallback(RetrievalQuery query,
                                                   KnowledgeBaseScope scope,
                                                   List<DegradationReason> reasons,
                                                   long startedAt) {
        List<KnowledgeChunk> context = fullContextSource.load(scope);
        if (withinBudget(context, query)) {
            return fullContextResult(query, context, reasons, startedAt);
        }
        reasons.add(DegradationReason.CONTEXT_BUDGET_EXCEEDED);
        return hybridResult(query, scope, reasons, startedAt);
    }

    private RetrievalResult fullContextResult(RetrievalQuery query,
                                               List<KnowledgeChunk> context,
                                               List<DegradationReason> reasons,
                                               long startedAt) {
        List<KnowledgeCitation> citations = context.stream().map(this::toCitation).toList();
        if (citations.isEmpty()) {
            reasons.add(DegradationReason.NO_RESULT);
        }
        return result(query, RetrievalMode.FULL_CONTEXT, citations, reasons, startedAt);
    }

    private boolean withinBudget(List<KnowledgeChunk> context, RetrievalQuery query) {
        long tokens = 0;
        for (KnowledgeChunk chunk : context) {
            tokens += tokenCounter.countTokens(chunk.content());
            if (tokens > query.contextBudget().availableInputTokens()) {
                return false;
            }
        }
        return true;
    }

    private RetrievalResult keywordResult(RetrievalQuery query,
                                           KnowledgeBaseScope scope,
                                           RetrievalMode selectedMode,
                                           List<DegradationReason> reasons,
                                           long startedAt) {
        try {
            List<RetrievalCandidate> candidates = safeList(
                    keywordRetriever.retrieve(query, scope, query.candidateK()));
            if (candidates.isEmpty()) {
                reasons.add(DegradationReason.NO_RESULT);
            }
            return result(query, selectedMode, toCitations(candidates, query.topK()), reasons, startedAt);
        } catch (RuntimeException exception) {
            reasons.add(DegradationReason.KEYWORD_UNAVAILABLE);
            reasons.add(DegradationReason.NO_RESULT);
            return result(query, selectedMode, List.of(), reasons, startedAt);
        }
    }

    private RetrievalResult vectorResult(RetrievalQuery query,
                                         KnowledgeBaseScope scope,
                                         List<DegradationReason> reasons,
                                         long startedAt) {
        try {
            List<RetrievalCandidate> candidates = safeList(
                    vectorRetriever.retrieve(query, scope, query.candidateK()));
            if (candidates.isEmpty()) {
                reasons.add(DegradationReason.NO_RESULT);
            }
            return result(query, RetrievalMode.VECTOR, toCitations(candidates, query.topK()), reasons, startedAt);
        } catch (RuntimeException exception) {
            reasons.add(DegradationReason.VECTOR_UNAVAILABLE);
            reasons.add(DegradationReason.NO_RESULT);
            return result(query, RetrievalMode.VECTOR, List.of(), reasons, startedAt);
        }
    }

    private RetrievalResult hybridResult(RetrievalQuery query,
                                         KnowledgeBaseScope scope,
                                         List<DegradationReason> reasons,
                                         long startedAt) {
        List<RetrievalCandidate> keywordCandidates = List.of();
        List<RetrievalCandidate> vectorCandidates = List.of();
        boolean keywordAvailable = true;
        boolean vectorAvailable = true;
        CompletableFuture<List<RetrievalCandidate>> keywordFuture = submit(
                () -> safeList(keywordRetriever.retrieve(query, scope, query.candidateK())));
        CompletableFuture<List<RetrievalCandidate>> vectorFuture = submit(
                () -> safeList(vectorRetriever.retrieve(query, scope, query.candidateK())));
        try {
            keywordCandidates = keywordFuture.join();
        } catch (RuntimeException exception) {
            keywordAvailable = false;
            reasons.add(DegradationReason.KEYWORD_UNAVAILABLE);
        }
        try {
            vectorCandidates = vectorFuture.join();
        } catch (RuntimeException exception) {
            vectorAvailable = false;
            reasons.add(DegradationReason.VECTOR_UNAVAILABLE);
        }

        if (!keywordAvailable && !vectorAvailable) {
            reasons.add(DegradationReason.NO_RESULT);
            return result(query, RetrievalMode.HYBRID, List.of(), reasons, startedAt);
        }
        if (!keywordAvailable) {
            return degradedSingleSource(query, RetrievalMode.VECTOR, vectorCandidates, reasons, startedAt);
        }
        if (!vectorAvailable) {
            return degradedSingleSource(query, RetrievalMode.KEYWORD, keywordCandidates, reasons, startedAt);
        }

        List<RetrievalCandidate> candidates = RrfRankFusion.fuse(
                keywordCandidates,
                vectorCandidates,
                new RrfRankFusion.Config(
                        rrfConfig.keywordWeight(),
                        rrfConfig.vectorWeight(),
                        rrfConfig.rrfRankConstant(),
                        query.topK(),
                        Integer.MAX_VALUE));
        if (candidates.isEmpty()) {
            reasons.add(DegradationReason.NO_RESULT);
        }
        return result(query, RetrievalMode.HYBRID, toCitations(candidates, query.topK()), reasons, startedAt);
    }

    private RetrievalResult degradedSingleSource(RetrievalQuery query,
                                                  RetrievalMode selectedMode,
                                                  List<RetrievalCandidate> candidates,
                                                  List<DegradationReason> reasons,
                                                  long startedAt) {
        if (candidates.isEmpty()) {
            reasons.add(DegradationReason.NO_RESULT);
        }
        return result(query, selectedMode, toCitations(candidates, query.topK()), reasons, startedAt);
    }

    private RetrievalResult result(RetrievalQuery query,
                                   RetrievalMode selectedMode,
                                   List<KnowledgeCitation> citations,
                                   List<DegradationReason> reasons,
                                   long startedAt) {
        return new RetrievalResult(
                query.requestedMode(),
                selectedMode,
                citations,
                !reasons.isEmpty(),
                reasons,
                (System.nanoTime() - startedAt) / 1_000_000,
                query.traceId());
    }

    private List<RetrievalCandidate> safeList(List<RetrievalCandidate> candidates) {
        return candidates == null ? List.of() : List.copyOf(candidates);
    }

    private CompletableFuture<List<RetrievalCandidate>> submit(Supplier<List<RetrievalCandidate>> task) {
        try {
            return CompletableFuture.supplyAsync(task, retrievalExecutor);
        } catch (RuntimeException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
    }

    private List<KnowledgeCitation> toCitations(List<RetrievalCandidate> candidates, int topK) {
        return candidates.stream().limit(topK).map(candidate -> new KnowledgeCitation(
                candidate.chunkId(),
                candidate.documentId(),
                candidate.documentVersionId(),
                candidate.title(),
                candidate.section(),
                candidate.source(),
                candidate.content(),
                candidate.vectorSimilarity(),
                candidate.keywordScore(),
                candidate.rrfScore(),
                candidate.finalScore(),
                candidate.exactMatch(),
                candidate.exactType())).toList();
    }

    private KnowledgeCitation toCitation(KnowledgeChunk chunk) {
        return new KnowledgeCitation(
                chunk.id(),
                chunk.documentId(),
                chunk.documentVersionId(),
                chunk.title(),
                chunk.section(),
                chunk.source(),
                chunk.content(),
                null,
                null,
                null,
                null,
                false,
                null);
    }
}
