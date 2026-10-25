package com.hartmann.domain.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static com.hartmann.domain.exception.Attributes.attributesOf;
import static com.hartmann.domain.exception.Attributes.outermostOf;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentServiceExceptionAttributesTest {
    private enum TestErrorCode implements ErrorCode {
        TEST_NOT_FOUND("Test not found"),
        TEST_UNAVAILABLE("Test unavailable");

        private final String title;

        TestErrorCode(String title) {
            this.title = title;
        }

        @Override
        public String title() {
            return title;
        }
    }

    private static class TestNotFoundException extends NotFoundException {
        TestNotFoundException(long refundId, long paymentId) {
            super(TestErrorCode.TEST_NOT_FOUND, "Refund not found", Map.of("refundId", refundId, "paymentId", paymentId));
        }
    }

    private static class TestUnavailableException extends UnavailableException {
        TestUnavailableException(long paymentId, Throwable cause) {
            super(TestErrorCode.TEST_UNAVAILABLE, "Refund failed", Map.of("paymentId", paymentId), cause);
        }
    }

    @Test
    void attributesOf_returnsTheAttributesOfASingleException() {
        var refundId = 7L;
        var paymentId = 3L;

        var actual = attributesOf(new TestNotFoundException(refundId, paymentId));

        assertThat(actual).isEqualTo(Map.of("refundId", refundId, "paymentId", paymentId));
    }

    @Test
    void attributesOf_mergesTheAttributesOfTheWholeCauseChainWithTheOuterValueWinning() {
        var refundId = 7L;
        var outerPaymentId = 4L;
        var exception = new TestUnavailableException(outerPaymentId,
                new IllegalStateException(new TestNotFoundException(refundId, 3L)));

        var actual = attributesOf(exception);

        assertThat(actual).isEqualTo(Map.of("refundId", refundId, "paymentId", outerPaymentId));
    }

    @Test
    void attributesOf_returnsNoAttributesForAnExceptionThatIsntAPaymentServiceException() {
        var actual = attributesOf(new IllegalStateException("connection refused"));

        assertThat(actual).isEmpty();
    }

    @Test
    void outermostOf_returnsTheExceptionItselfWhenItIsAPaymentServiceException() {
        var exception = new TestUnavailableException(4L, new TestNotFoundException(7L, 3L));

        var actual = outermostOf(exception);

        assertThat(actual).containsSame(exception);
    }

    @Test
    void outermostOf_returnsTheOutermostPaymentServiceExceptionInTheCauseChain() {
        var expected = new TestUnavailableException(4L, new TestNotFoundException(7L, 3L));

        var actual = outermostOf(new IllegalStateException(expected));

        assertThat(actual).containsSame(expected);
    }

    @Test
    void outermostOf_returnsNothingForAChainWithoutAPaymentServiceException() {
        var actual = outermostOf(new IllegalStateException(new RuntimeException("connection refused")));

        assertThat(actual).isEmpty();
    }
}
