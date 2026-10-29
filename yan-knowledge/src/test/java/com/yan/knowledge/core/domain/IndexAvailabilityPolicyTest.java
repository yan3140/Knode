package com.yan.knowledge.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexAvailabilityPolicyTest {

    @Test
    void shouldTreatDisabledVectorIndexAsNotRequired() {
        IndexAvailabilityPolicy policy = new IndexAvailabilityPolicy(true, false, true);
        IndexAvailability availability = new IndexAvailability(
                IndexAvailabilityState.READY,
                IndexAvailabilityState.NOT_REQUIRED);

        assertThat(policy.resolveStatus(availability)).isEqualTo(DocumentVersionStatus.READY);
    }

    @Test
    void shouldPublishPartialWhenOptionalVectorIndexFails() {
        IndexAvailabilityPolicy policy = new IndexAvailabilityPolicy(true, false, true);
        IndexAvailability availability = new IndexAvailability(
                IndexAvailabilityState.READY,
                IndexAvailabilityState.FAILED);

        assertThat(policy.resolveStatus(availability)).isEqualTo(DocumentVersionStatus.PARTIAL_READY);
    }

    @Test
    void shouldRejectPartialWhenRequiredKeywordIndexFails() {
        IndexAvailabilityPolicy policy = new IndexAvailabilityPolicy(true, false, true);
        IndexAvailability availability = new IndexAvailability(
                IndexAvailabilityState.FAILED,
                IndexAvailabilityState.READY);

        assertThat(policy.resolveStatus(availability)).isEqualTo(DocumentVersionStatus.FAILED);
    }

    @Test
    void shouldRemainIndexingWhileAnEnabledIndexIsPending() {
        IndexAvailabilityPolicy policy = new IndexAvailabilityPolicy(true, false, true);
        IndexAvailability availability = new IndexAvailability(
                IndexAvailabilityState.PENDING,
                IndexAvailabilityState.PENDING);

        assertThat(policy.resolveStatus(availability)).isEqualTo(DocumentVersionStatus.INDEXING);
    }
}
