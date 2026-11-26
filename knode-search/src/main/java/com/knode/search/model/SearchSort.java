package com.knode.search.model;

import java.util.Locale;

public enum SearchSort {
    RELEVANCE,
    LATEST,
    HOT;

    public static SearchSort from(String value) {
        if (value == null || value.isBlank()) {
            return RELEVANCE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
