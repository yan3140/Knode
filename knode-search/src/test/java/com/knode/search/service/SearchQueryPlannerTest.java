package com.knode.search.service;

import com.knode.search.config.SearchCacheProperties;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.config.SearchQueryProperties;
import com.knode.search.model.SearchBusinessException;
import com.knode.search.model.SearchErrorCode;
import com.knode.search.model.SearchQuery;
import com.knode.search.model.SearchSort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryPlannerTest {

    private SearchQueryPlanner newPlanner(boolean cacheEnabled, String indexName, String env) {
        SearchQueryProperties queryProps = new SearchQueryProperties();
        SearchCacheProperties cacheProps = new SearchCacheProperties();
        cacheProps.setEnabled(cacheEnabled);
        SearchElasticsearchProperties esProps = new SearchElasticsearchProperties();
        esProps.setIndex(indexName);
        return new SearchQueryPlanner(queryProps, cacheProps, esProps, env);
    }

    @Test
    void normalizesDefaultsAndClampsPageSize99To20() {
        SearchQueryPlanner planner = newPlanner(true, "knode_test_articles", "local");

        SearchQuery defaults = planner.plan("  Spring  ", null, null, null);
        assertThat(defaults.keyword()).isEqualTo("Spring");
        assertThat(defaults.pageNum()).isEqualTo(1);
        assertThat(defaults.pageSize()).isEqualTo(5);
        assertThat(defaults.sort()).isEqualTo(SearchSort.RELEVANCE);
        assertThat(defaults.from()).isEqualTo(0);
        assertThat(defaults.cacheCandidate()).isTrue();
        assertThat(defaults.resultCacheKey()).startsWith("knode:local:v1:search:result:");
        assertThat(defaults.admissionCacheKey()).startsWith("knode:local:v1:search:admit:");

        SearchQuery clamped = planner.plan("Spring", 1, 99, "HOT");
        assertThat(clamped.pageSize()).isEqualTo(20);
        assertThat(clamped.sort()).isEqualTo(SearchSort.HOT);
        assertThat(clamped.cacheCandidate()).isTrue();
    }

    @Test
    void rejectsPageNum51AndInvalidKeywordOrSortWithCode515() {
        SearchQueryPlanner planner = newPlanner(true, "knode_test_articles", "local");

        assertThatThrownBy(() -> planner.plan("Spring", 51, 5, "relevance"))
                .isInstanceOf(SearchBusinessException.class)
                .extracting(ex -> ((SearchBusinessException) ex).getErrorCode())
                .isEqualTo(SearchErrorCode.PARAM_ERROR);

        assertThatThrownBy(() -> planner.plan("   ", 1, 5, "relevance"))
                .isInstanceOf(SearchBusinessException.class)
                .extracting(ex -> ((SearchBusinessException) ex).getErrorCode())
                .isEqualTo(SearchErrorCode.PARAM_ERROR);

        assertThatThrownBy(() -> planner.plan("a".repeat(41), 1, 5, "relevance"))
                .isInstanceOf(SearchBusinessException.class)
                .extracting(ex -> ((SearchBusinessException) ex).getErrorCode())
                .isEqualTo(SearchErrorCode.PARAM_ERROR);

        assertThatThrownBy(() -> planner.plan("Spring", 1, 5, "unknown_sort"))
                .isInstanceOf(SearchBusinessException.class)
                .extracting(ex -> ((SearchBusinessException) ex).getErrorCode())
                .isEqualTo(SearchErrorCode.PARAM_ERROR);
    }

    @Test
    void enforcesCacheCandidateRulesAndIsolatedIndexFingerprint() {
        SearchQueryPlanner enabled = newPlanner(true, "idx_a", "stage");
        SearchQueryPlanner otherIndex = newPlanner(true, "idx_b", "stage");
        SearchQueryPlanner disabled = newPlanner(false, "idx_a", "stage");

        assertThat(enabled.plan("Spring", 2, 5, "latest").cacheCandidate()).isFalse();
        assertThat(enabled.plan("a", 1, 5, "relevance").cacheCandidate()).isFalse();
        assertThat(enabled.plan("AbCdEfGhIjKlMnOpQrStUvWxYz1234", 1, 5, "relevance").cacheCandidate()).isFalse();
        assertThat(disabled.plan("Spring", 1, 5, "relevance").cacheCandidate()).isFalse();

        SearchQuery qA = enabled.plan("Spring", 1, 5, "relevance");
        SearchQuery qB = otherIndex.plan("Spring", 1, 5, "relevance");
        assertThat(qA.resultCacheKey()).startsWith("knode:stage:v1:search:result:");
        assertThat(qA.resultCacheKey()).isNotEqualTo(qB.resultCacheKey());
    }
}
