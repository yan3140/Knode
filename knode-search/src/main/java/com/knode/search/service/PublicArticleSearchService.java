package com.knode.search.service;

import com.knode.search.cache.ArticleSearchCache;
import com.knode.search.client.CanonicalIdParser;
import com.knode.search.client.ContentVisibilityClient;
import com.knode.search.client.VisibilityDecision;
import com.knode.search.config.SearchQueryProperties;
import com.knode.search.metrics.SearchMetrics;
import com.knode.search.model.PageResult;
import com.knode.search.model.QueryBudget;
import com.knode.search.model.SearchArticleVo;
import com.knode.search.model.SearchBusinessException;
import com.knode.search.model.SearchCandidatePage;
import com.knode.search.model.SearchCandidateRow;
import com.knode.search.model.SearchErrorCode;
import com.knode.search.model.SearchQuery;
import com.knode.search.repository.ArticleSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PublicArticleSearchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublicArticleSearchService.class);

    private final SearchQueryPlanner queryPlanner;
    private final ArticleSearchCache articleSearchCache;
    private final ArticleSearchRepository articleSearchRepository;
    private final ContentVisibilityClient contentVisibilityClient;
    private final SearchQueryProperties queryProperties;
    private final SearchMetrics searchMetrics;

    public PublicArticleSearchService(
            SearchQueryPlanner queryPlanner,
            ArticleSearchCache articleSearchCache,
            ArticleSearchRepository articleSearchRepository,
            ContentVisibilityClient contentVisibilityClient,
            SearchQueryProperties queryProperties,
            SearchMetrics searchMetrics) {
        this.queryPlanner = queryPlanner;
        this.articleSearchCache = articleSearchCache;
        this.articleSearchRepository = articleSearchRepository;
        this.contentVisibilityClient = contentVisibilityClient;
        this.queryProperties = queryProperties;
        this.searchMetrics = searchMetrics;
    }

    public PageResult search(String keyword, Integer pageNum, Integer pageSize, String sort, String requestId) {
        SearchQuery query = queryPlanner.plan(keyword, pageNum, pageSize, sort);
        long startNanos = System.nanoTime();
        QueryBudget budget = QueryBudget.start(Duration.ofMillis(Math.max(1L, queryProperties.getTotalTimeoutMs())));

        try {
            boolean fromCache = false;
            SearchCandidatePage candidatePage;
            Optional<SearchCandidatePage> cached = articleSearchCache.get(query, budget);
            if (cached.isPresent()) {
                candidatePage = cached.get();
                fromCache = true;
            } else {
                if (budget.isExpired()) {
                    throw new SearchBusinessException(SearchErrorCode.SEARCH_SERVICE_UNAVAILABLE);
                }
                candidatePage = articleSearchRepository.search(query, budget);
            }

            if (candidatePage == null || candidatePage.rows() == null || candidatePage.total() < 0L) {
                throw new SearchBusinessException(SearchErrorCode.SEARCH_SERVICE_UNAVAILABLE);
            }

            PageResult result;
            int excludedCount = 0;
            if (candidatePage.rows().isEmpty()) {
                result = new PageResult(List.of(), Math.max(0L, candidatePage.total()));
            } else {
                VisibilityFilterResult filtered = filterVisibleCandidates(candidatePage.rows(), budget, requestId);
                excludedCount = filtered.excludedCount();
                long adjustedTotal = Math.max(0L, candidatePage.total() - excludedCount);
                result = new PageResult(filtered.visibleRows(), adjustedTotal);
            }

            searchMetrics.recordVisibilityExcluded(excludedCount);
            updateCacheAfterVerification(query, candidatePage, fromCache, excludedCount, budget);

            searchMetrics.recordQuery(true, System.nanoTime() - startNanos);
            return result;
        } catch (SearchBusinessException ex) {
            searchMetrics.recordQuery(false, System.nanoTime() - startNanos);
            throw ex;
        } catch (Exception ex) {
            searchMetrics.recordQuery(false, System.nanoTime() - startNanos);
            LOGGER.warn("event=public_search_failed pageNum={} pageSize={} sort={} errorType={}",
                    query.pageNum(), query.pageSize(), query.sort(), ex.getClass().getSimpleName());
            throw new SearchBusinessException(SearchErrorCode.SEARCH_SERVICE_UNAVAILABLE, ex);
        }
    }

    private VisibilityFilterResult filterVisibleCandidates(
            List<SearchCandidateRow> candidates,
            QueryBudget budget,
            String requestId) {
        Set<String> validArticleIds = new LinkedHashSet<>();
        for (SearchCandidateRow row : candidates) {
            if (row == null || row.id() == null || row.id() <= 0L) {
                continue;
            }
            if (CanonicalIdParser.normalizePositiveDecimalString(row.sourceRevision()) == null) {
                continue;
            }
            validArticleIds.add(Long.toString(row.id()));
        }

        Map<String, VisibilityDecision> decisions = validArticleIds.isEmpty()
                ? Map.of()
                : contentVisibilityClient.checkPublicSearch(List.copyOf(validArticleIds), budget, requestId);

        List<SearchArticleVo> visibleRows = new ArrayList<>(candidates.size());
        int excludedCount = 0;
        for (SearchCandidateRow row : candidates) {
            if (row == null || row.id() == null || row.id() <= 0L) {
                excludedCount++;
                continue;
            }
            String candidateRevision = CanonicalIdParser.normalizePositiveDecimalString(row.sourceRevision());
            if (candidateRevision == null) {
                excludedCount++;
                continue;
            }
            VisibilityDecision decision = decisions.get(Long.toString(row.id()));
            if (decision == null) {
                throw new SearchBusinessException(SearchErrorCode.SEARCH_SERVICE_UNAVAILABLE);
            }
            if (decision.isPublicReadableMatchingRevision(candidateRevision)) {
                visibleRows.add(row.toPublicVo());
            } else {
                excludedCount++;
            }
        }
        return new VisibilityFilterResult(visibleRows, excludedCount);
    }

    private void updateCacheAfterVerification(
            SearchQuery query,
            SearchCandidatePage candidatePage,
            boolean fromCache,
            int excludedCount,
            QueryBudget budget) {
        if (excludedCount > 0) {
            if (fromCache) {
                articleSearchCache.evict(query);
            }
            return;
        }
        if (!fromCache) {
            articleSearchCache.put(query, candidatePage, budget);
        }
    }

    private record VisibilityFilterResult(List<SearchArticleVo> visibleRows, int excludedCount) {
    }
}
