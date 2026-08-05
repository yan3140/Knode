package com.yan.reliabletask;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReliableTaskPolicyTest {
    @Test
    void retryDelayShouldUseExponentialBackoffWithCeiling() {
        ReliableTaskPolicy policy = new ReliableTaskPolicy(8, 10, 300);

        assertThat(policy.nextRetryDelaySeconds(0)).isEqualTo(10);
        assertThat(policy.nextRetryDelaySeconds(1)).isEqualTo(20);
        assertThat(policy.nextRetryDelaySeconds(2)).isEqualTo(40);
        assertThat(policy.nextRetryDelaySeconds(10)).isEqualTo(300);
    }

    @Test
    void willDeadLetterShouldTurnTrueOnLastAllowedFailure() {
        ReliableTaskPolicy policy = new ReliableTaskPolicy(3, 10, 300);

        assertThat(policy.willDeadLetter(0)).isFalse();
        assertThat(policy.willDeadLetter(1)).isFalse();
        assertThat(policy.willDeadLetter(2)).isTrue();
    }

    @Test
    void safeErrorShouldTruncateLongMessages() {
        ReliableTaskPolicy policy = new ReliableTaskPolicy(3, 10, 300);

        assertThat(policy.safeError("x".repeat(1200))).hasSize(1000);
    }
}
