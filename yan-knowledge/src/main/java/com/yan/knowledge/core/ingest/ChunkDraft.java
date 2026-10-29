package com.yan.knowledge.core.ingest;

import java.util.Map;
import java.util.Objects;

public record ChunkDraft(
        int chunkIndex,
        String section,
        String content,
        Map<String, String> metadata
) {
    public ChunkDraft {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        if (Objects.requireNonNull(section, "section must not be null").isBlank()) {
            throw new IllegalArgumentException("section must not be blank");
        }
        Objects.requireNonNull(content, "content must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
