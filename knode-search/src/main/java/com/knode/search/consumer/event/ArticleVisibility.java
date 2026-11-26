package com.knode.search.consumer.event;

public enum ArticleVisibility {
    NONE,
    PUBLIC,
    WITHDRAWN,
    DELETED;

    public static ArticleVisibility fromEventValue(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "PUBLIC" -> PUBLIC;
            case "WITHDRAWN" -> WITHDRAWN;
            case "DELETED" -> DELETED;
            default -> null;
        };
    }
}
