package com.hartmann.adapter.in.kafka;

import com.hartmann.domain.exception.ConflictException;
import com.hartmann.domain.exception.ErrorCode;
import com.hartmann.domain.exception.TransientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

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
            super(TestErrorCode.TEST_CONFLICT, "conflict", Map.of(), cause);
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
}
