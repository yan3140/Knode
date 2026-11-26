package com.knode.search.service;

import com.knode.search.config.SearchCacheProperties;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.config.SearchQueryProperties;
import com.knode.search.model.SearchBusinessException;
import com.knode.search.model.SearchErrorCode;
import com.knode.search.model.SearchQuery;
import com.knode.search.model.SearchSort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Component
public class SearchQueryPlanner {
    private static final int MIN_CACHE_KEYWORD_LENGTH = 2;
    private static final int RANDOM_KEYWORD_MIN_LENGTH = 24;
    private static final double RANDOM_ALNUM_RATIO = 0.8d;
    private static final double RANDOM_UNIQUE_RATIO = 0.7d;

    private final SearchQueryProperties queryProperties;
    private final SearchCacheProperties cacheProperties;
    private final SearchElasticsearchProperties elasticsearchProperties;
    private final String environment;

    public SearchQueryPlanner(
            SearchQueryProperties queryProperties,
            SearchCacheProperties cacheProperties,
            SearchElasticsearchProperties elasticsearchProperties,
            @Value("${knode.environment:local}") String environment) {
        this.queryProperties = queryProperties;
        this.cacheProperties = cacheProperties;
        this.elasticsearchProperties = elasticsearchProperties;
        this.environment = sanitizeEnvironment(environment);
    }

    public SearchQuery plan(String rawKeyword, Integer rawPageNum, Integer rawPageSize, String rawSort) {
        String keyword = normalizeKeyword(rawKeyword);
        SearchSort sort = SearchSort.from(rawSort);
        if (sort == null) {
            throw new SearchBusinessException(SearchErrorCode.PARAM_ERROR);
        }

        int pageNum = rawPageNum == null || rawPageNum < 1 ? 1 : rawPageNum;
        if (pageNum > queryProperties.getMaxPageNum()) {
            throw new SearchBusinessException(SearchErrorCode.PARAM_ERROR);
        }

        int requestedPageSize = rawPageSize == null || rawPageSize < 1
                ? queryProperties.getDefaultPageSize()
                : rawPageSize;
        int pageSize = Math.min(requestedPageSize, queryProperties.getMaxPageSize());

        boolean highlightContent = queryProperties.isHighlightContent();
        boolean cacheCandidate = isCacheCandidate(keyword, pageNum, pageSize);
        String fingerprint = buildFingerprint(
                elasticsearchProperties.getIndex(),
                keyword,
                pageNum,
                pageSize,
                sort,
                highlightContent);
        String prefix = "knode:" + environment + ":v1:search:";
        return new SearchQuery(
                keyword,
                pageNum,
                pageSize,
                sort,
                highlightContent,
                queryProperties.getTotalHitThreshold(),
                cacheCandidate,
                prefix + "result:" + fingerprint,
                prefix + "admit:" + fingerprint
        );
    }

    private String normalizeKeyword(String rawKeyword) {
        if (rawKeyword == null) {
            throw new SearchBusinessException(SearchErrorCode.PARAM_ERROR);
        }
        String trimmed = rawKeyword.trim();
        if (trimmed.isEmpty() || trimmed.length() > queryProperties.getMaxKeywordLength()) {
            throw new SearchBusinessException(SearchErrorCode.PARAM_ERROR);
        }
        return trimmed;
    }

    private boolean isCacheCandidate(String keyword, int pageNum, int pageSize) {
        return cacheProperties.isEnabled()
                && pageNum == 1
                && pageSize <= cacheProperties.getMaxPageSize()
                && keyword.length() >= MIN_CACHE_KEYWORD_LENGTH
                && !looksRandom(keyword);
    }

    static boolean looksRandom(String keyword) {
        if (keyword == null || keyword.length() < RANDOM_KEYWORD_MIN_LENGTH) {
            return false;
        }
        int length = keyword.length();
        int alnumCount = 0;
        Set<Character> uniqueChars = new HashSet<>();
        for (int i = 0; i < length; i++) {
            char ch = keyword.charAt(i);
            if (Character.isWhitespace(ch)) {
                return false;
            }
            if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                alnumCount++;
            }
            uniqueChars.add(ch);
        }
        double alnumRatio = (double) alnumCount / (double) length;
        double uniqueRatio = (double) uniqueChars.size() / (double) length;
        return alnumRatio > RANDOM_ALNUM_RATIO && uniqueRatio > RANDOM_UNIQUE_RATIO;
    }

    private static String buildFingerprint(
            String indexName,
            String keyword,
            int pageNum,
            int pageSize,
            SearchSort sort,
            boolean highlightContent) {
        String raw = (indexName == null ? "" : indexName)
                + "|" + keyword
                + "|" + pageNum
                + "|" + pageSize
                + "|" + sort.name()
                + "|" + highlightContent;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String sanitizeEnvironment(String rawEnvironment) {
        if (rawEnvironment == null || rawEnvironment.isBlank()) {
            return "local";
        }
        String normalized = rawEnvironment.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-");
        return normalized.isEmpty() ? "local" : normalized;
    }
}
