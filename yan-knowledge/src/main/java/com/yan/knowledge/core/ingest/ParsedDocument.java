package com.yan.knowledge.core.ingest;

import java.util.Objects;

public record ParsedDocument(
        String title,
        String source,
        String content
) {
    public ParsedDocument {
        requireText(title, "title");
        requireText(source, "source");
        Objects.requireNonNull(content, "content must not be null");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
