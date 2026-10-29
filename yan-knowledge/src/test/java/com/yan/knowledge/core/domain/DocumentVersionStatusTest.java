package com.yan.knowledge.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentVersionStatusTest {

    @Test
    void shouldAllowOnlyDocumentedLifecycleTransitions() {
        assertThat(DocumentVersionStatus.RECEIVED.canTransitionTo(DocumentVersionStatus.INDEXING)).isTrue();
        assertThat(DocumentVersionStatus.INDEXING.canTransitionTo(DocumentVersionStatus.READY)).isTrue();
        assertThat(DocumentVersionStatus.INDEXING.canTransitionTo(DocumentVersionStatus.PARTIAL_READY)).isTrue();
        assertThat(DocumentVersionStatus.PARTIAL_READY.canTransitionTo(DocumentVersionStatus.INDEXING)).isTrue();
        assertThat(DocumentVersionStatus.FAILED.canTransitionTo(DocumentVersionStatus.INDEXING)).isTrue();
        assertThat(DocumentVersionStatus.READY.canTransitionTo(DocumentVersionStatus.INDEXING)).isFalse();
    }

    @Test
    void shouldMarkOnlyReadyStatesAsUsable() {
        assertThat(DocumentVersionStatus.READY.isUsable()).isTrue();
        assertThat(DocumentVersionStatus.PARTIAL_READY.isUsable()).isTrue();
        assertThat(DocumentVersionStatus.INDEXING.isUsable()).isFalse();
        assertThat(DocumentVersionStatus.FAILED.isUsable()).isFalse();
    }
}
