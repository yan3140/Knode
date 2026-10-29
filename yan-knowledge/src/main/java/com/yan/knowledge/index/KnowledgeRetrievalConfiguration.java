package com.yan.knowledge.index;

import com.yan.knowledge.core.port.ExactSignalExtractor;
import com.yan.knowledge.core.port.FullContextSource;
import com.yan.knowledge.core.port.KeywordRetriever;
import com.yan.knowledge.core.port.KnowledgeScopeResolver;
import com.yan.knowledge.core.port.TokenCounter;
import com.yan.knowledge.core.port.VectorRetriever;
import com.yan.knowledge.core.retrieval.AdaptiveRetriever;
import com.yan.knowledge.core.retrieval.DefaultAdaptiveRetriever;
import com.yan.knowledge.core.retrieval.RrfRankFusion;
import com.yan.knowledge.core.retrieval.RelevanceThresholds;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class KnowledgeRetrievalConfiguration {
    @Bean(destroyMethod = "shutdown")
    ExecutorService knowledgeRetrievalExecutor() {
        return new ThreadPoolExecutor(8, 8, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    AdaptiveRetriever adaptiveRetriever(FullContextSource fullContextSource, KeywordRetriever keywordRetriever,
                                        VectorRetriever vectorRetriever, KnowledgeScopeResolver scopeResolver,
                                        TokenCounter tokenCounter, ExactSignalExtractor exactSignalExtractor,
                                        KnowledgeSearchProperties searchProperties,
                                        ExecutorService knowledgeRetrievalExecutor) {
        return new DefaultAdaptiveRetriever(fullContextSource, keywordRetriever, vectorRetriever, scopeResolver,
                tokenCounter, exactSignalExtractor, new RrfRankFusion.Config(1.0, 1.0, 60, 10, 3),
                new RelevanceThresholds(
                        searchProperties.getVectorCandidateSimilarity(),
                        searchProperties.getVectorEvidenceSimilarity(),
                        searchProperties.getVectorStrongSimilarity()), knowledgeRetrievalExecutor);
    }
}
