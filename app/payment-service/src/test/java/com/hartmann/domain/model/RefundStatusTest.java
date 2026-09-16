package com.hartmann.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RefundStatusTest {

    @Test
    void isTerminal_returnsFalseForInitiated() {
        assertThat(RefundStatus.INITIATED.isTerminal()).isFalse();
    }

    @Test
    void isTerminal_returnsFalseForPending() {
        assertThat(RefundStatus.PENDING.isTerminal()).isFalse();
    }

    @Test
    void isTerminal_returnsTrueForSucceeded() {
        assertThat(RefundStatus.SUCCEEDED.isTerminal()).isTrue();
    }

    @Test
    void isTerminal_returnsTrueForFailed() {
        assertThat(RefundStatus.FAILED.isTerminal()).isTrue();
    }
}
