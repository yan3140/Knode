package com.yan.knowledge.core.domain;

import java.util.Objects;

/** 记录关键词和向量索引各自的状态，避免用一个总状态掩盖可降级能力。 */
public record IndexAvailability(
        IndexAvailabilityState keyword,
        IndexAvailabilityState vector
) {
    public IndexAvailability {
        Objects.requireNonNull(keyword, "keyword availability must not be null");
        Objects.requireNonNull(vector, "vector availability must not be null");
    }

    public static IndexAvailability from(IndexProfile profile,
                                         IndexAvailabilityState keyword,
                                         IndexAvailabilityState vector) {
        Objects.requireNonNull(profile, "index profile must not be null");
        return new IndexAvailability(
                profile.keywordEnabled() ? keyword : IndexAvailabilityState.NOT_REQUIRED,
                profile.vectorEnabled() ? vector : IndexAvailabilityState.NOT_REQUIRED);
    }

    public boolean hasUsableIndex() {
        return keyword == IndexAvailabilityState.READY || vector == IndexAvailabilityState.READY;
    }

    public boolean hasPendingIndex() {
        return keyword == IndexAvailabilityState.PENDING || vector == IndexAvailabilityState.PENDING;
    }

    public boolean allEnabledIndexesReady() {
        boolean hasEnabledIndex = keyword != IndexAvailabilityState.NOT_REQUIRED
                || vector != IndexAvailabilityState.NOT_REQUIRED;
        return hasEnabledIndex && isReadyOrNotRequired(keyword) && isReadyOrNotRequired(vector);
    }

    private boolean isReadyOrNotRequired(IndexAvailabilityState state) {
        return state == IndexAvailabilityState.READY || state == IndexAvailabilityState.NOT_REQUIRED;
    }
}
