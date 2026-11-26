package com.knode.search.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SearchCandidateRow(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String sourceRevision,
        String title,
        String summary,
        @JsonSerialize(using = ToStringSerializer.class) Long categoryId,
        String categoryName,
        List<String> tagNames,
        String thumbnail,
        @JsonSerialize(using = ToStringSerializer.class) Long viewCount,
        @JsonSerialize(using = ToStringSerializer.class) Long createTimeEpochMillis,
        Float score,
        Map<String, List<String>> highlights
) {
    public SearchArticleVo toPublicVo() {
        return new SearchArticleVo(
                id,
                title,
                summary,
                categoryId,
                categoryName,
                tagNames == null ? List.of() : List.copyOf(tagNames),
                thumbnail,
                viewCount == null ? 0L : viewCount,
                createTimeEpochMillis == null ? null : new Date(createTimeEpochMillis),
                score,
                highlights == null ? Map.of() : copyHighlights(highlights)
        );
    }

    private static Map<String, List<String>> copyHighlights(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
        }
        return copy;
    }
}
