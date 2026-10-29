package com.yan.knowledge.core.retrieval;

import java.util.Objects;

public record ExactSignal(
        ExactMatchType type,
        String value
) {
    public ExactSignal {
        Objects.requireNonNull(type, "type must not be null");
        if (Objects.requireNonNull(value, "value must not be null").isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
