package com.yan.search.service;

import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.search.config.SearchQueryProperties;
import com.yan.search.model.SearchQuery;
import com.yan.search.model.SearchSort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class SearchQueryPlanner {

    private final SearchQueryProperties properties;

    public SearchQueryPlanner(SearchQueryProperties properties) {
        this.properties = properties;
    }

    public SearchQuery plan(String keyword, Integer pageNum, Integer pageSize, String sort) {
        if (!StringUtils.hasText(keyword)) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        String normalizedKeyword = keyword.trim();
        if (normalizedKeyword.length() > properties.getMaxKeywordLength()) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        SearchSort searchSort = SearchSort.from(sort);
        if (searchSort == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        int safePageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int defaultPageSize = Math.max(1, properties.getDefaultPageSize());
        int safePageSize = pageSize == null || pageSize < 1 ? defaultPageSize : Math.min(pageSize, properties.getMaxPageSize());
        if (safePageNum > properties.getMaxPageNum()) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        boolean cacheCandidate = cacheCandidate(normalizedKeyword, safePageNum, safePageSize);
        return new SearchQuery(
                normalizedKeyword,
                safePageNum,
                safePageSize,
                searchSort,
                properties.isHighlightContent(),
                properties.getTotalHitThreshold(),
                cacheCandidate,
                cacheKey(normalizedKeyword, safePageNum, safePageSize, searchSort)
        );
    }

    private boolean cacheCandidate(String keyword, int pageNum, int pageSize) {
        SearchQueryProperties.Cache cache = properties.getCache();
        return cache.isEnabled()
                && pageNum == 1
                && pageSize <= cache.getMaxPageSize()
                && keyword.length() >= 2
                && !looksRandom(keyword);
    }

    private boolean looksRandom(String keyword) {
        if (keyword.length() < 24 || keyword.contains(" ")) {
            return false;
        }
        long letterOrDigit = keyword.chars().filter(Character::isLetterOrDigit).count();
        long unique = keyword.chars().distinct().count();
        return letterOrDigit * 1.0 / keyword.length() > 0.8
                && unique * 1.0 / keyword.length() > 0.7;
    }

    private String cacheKey(String keyword, int pageNum, int pageSize, SearchSort sort) {
        String raw = keyword + "|" + pageNum + "|" + pageSize + "|" + sort.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("blog:search:result:");
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
