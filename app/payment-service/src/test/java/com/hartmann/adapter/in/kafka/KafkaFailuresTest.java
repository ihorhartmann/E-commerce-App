package com.hartmann.adapter.in.kafka;

import com.hartmann.domain.exception.ConflictException;
import com.hartmann.domain.exception.ErrorCode;
import com.hartmann.domain.exception.TransientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KafkaFailuresTest {
    private enum TestErrorCode implements ErrorCode {
        TEST_CONFLICT("Test conflict"),
        TEST_TRANSIENT("Test transient");

        private final String title;

        TestErrorCode(String title) {
            this.title = title;
        }

        @Override
        public String title() {
            return title;
        }
    }

    private static class TestConflictException extends ConflictException {
        TestConflictException(Throwable cause) {
            this(cause, Map.of());
        }

        TestConflictException(Throwable cause, Map<String, ?> attributes) {
            super(TestErrorCode.TEST_CONFLICT, "conflict", attributes, cause);
        }
    }

    private static class TestTransientException extends TransientException {
        TestTransientException(Throwable cause) {
            super(TestErrorCode.TEST_TRANSIENT, "transient", Map.of(), cause);
        }
    }

    @Test
    void isPermanentFailure_isTrueForAPermanentException() {
        assertThat(KafkaFailures.isPermanentFailure(new TestConflictException(null))).isTrue();
    }

    @Test
    void isPermanentFailure_isTrueForAPermanentExceptionWrappedInOneThatIsntOurs() {
        assertThat(KafkaFailures.isPermanentFailure(new RuntimeException("listener failed", new TestConflictException(null))))
                .isTrue();
    }

    @Test
    void isPermanentFailure_isDecidedByTheOutermostExceptionOfOurs() {
        assertThat(KafkaFailures.isPermanentFailure(new TestTransientException(new TestConflictException(null)))).isFalse();
    }

    @Test
    void isPermanentFailure_isFalseForAnExceptionThatIsntOurs() {
        assertThat(KafkaFailures.isPermanentFailure(new IllegalStateException("database unavailable"))).isFalse();
    }

    @Test
    void failureKeyValues_describesTheOutermostExceptionOfOursWithItsMessageCodeAndTheChainsAttributes() {
        var paymentId = 7L;
        var conflict = new TestConflictException(null, Map.of("paymentId", paymentId));

        var actual = KafkaFailures.failureKeyValues(new RuntimeException("listener failed", conflict));

        var expected = Map.of("exceptionMessage", conflict.getMessage(), "code", conflict.code().code(), "paymentId", paymentId);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void failureKeyValues_isOnlyTheMessageForAnExceptionThatIsntOurs() {
        var message = "database unavailable";

        assertThat(KafkaFailures.failureKeyValues(new IllegalStateException(message))).isEqualTo(Map.of("exceptionMessage", message));
    }

    @Test
    void failureKeyValues_leavesOutAKeyTheMdcHasAlready() {
        var conflict = new TestConflictException(null, Map.of("messageType", "RefundRequested", "paymentId", 8L));

        Map<String, Object> actual;
        try (var _ = MDC.putCloseable("messageType", "RefundRequested")) {
            actual = KafkaFailures.failureKeyValues(conflict);
        }

        assertThat(actual.keySet()).containsExactlyInAnyOrder("exceptionMessage", "code", "paymentId");
    }
}
