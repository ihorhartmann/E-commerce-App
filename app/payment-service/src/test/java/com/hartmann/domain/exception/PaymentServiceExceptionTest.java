package com.hartmann.domain.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentServiceExceptionTest {
    private enum TestErrorCode implements ErrorCode {
        TEST_ERROR;

        @Override
        public String title() {
            return "Test error";
        }
    }

    private static class TestInvalidException extends InvalidException {
        TestInvalidException() {
            super(TestErrorCode.TEST_ERROR, "invalid");
        }
    }

    private static class TestNotFoundException extends NotFoundException {
        TestNotFoundException() {
            super(TestErrorCode.TEST_ERROR, "not found");
        }
    }

    private static class TestConflictException extends ConflictException {
        TestConflictException() {
            super(TestErrorCode.TEST_ERROR, "conflict");
        }
    }

    private static class TestTransientException extends TransientException {
        TestTransientException() {
            super(TestErrorCode.TEST_ERROR, "transient");
        }
    }

    private static class TestUnavailableException extends UnavailableException {
        TestUnavailableException() {
            super(TestErrorCode.TEST_ERROR, "unavailable");
        }
    }

    private static class TestInternalException extends PaymentServiceException {
        TestInternalException() {
            super(TestErrorCode.TEST_ERROR, "internal", null, Map.of(), null);
        }
    }

    @Test
    void isPermanent_isTrueForAnInvalidRequestAMissingEntityAndABusinessConflict() {
        assertThat(new TestInvalidException().isPermanent()).isTrue();
        assertThat(new TestNotFoundException().isPermanent()).isTrue();
        assertThat(new TestConflictException().isPermanent()).isTrue();
    }

    @Test
    void isPermanent_isFalseForATransientFailureAnUnavailableDependencyAndAnInternalFailure() {
        assertThat(new TestTransientException().isPermanent()).isFalse();
        assertThat(new TestUnavailableException().isPermanent()).isFalse();
        assertThat(new TestInternalException().isPermanent()).isFalse();
    }

    @Test
    void isRetryable_isTrueForATransientFailureAndAnUnavailableDependency() {
        assertThat(new TestTransientException().isRetryable()).isTrue();
        assertThat(new TestUnavailableException().isRetryable()).isTrue();
    }

    @Test
    void isRetryable_isFalseForAPermanentFailureAndAnInternalFailure() {
        assertThat(new TestInvalidException().isRetryable()).isFalse();
        assertThat(new TestNotFoundException().isRetryable()).isFalse();
        assertThat(new TestConflictException().isRetryable()).isFalse();
        assertThat(new TestInternalException().isRetryable()).isFalse();
    }

    @Test
    void code_prefixesTheNameWithTheService() {
        assertThat(TestErrorCode.TEST_ERROR.code()).isEqualTo("PAYMENT.TEST_ERROR");
    }
}
