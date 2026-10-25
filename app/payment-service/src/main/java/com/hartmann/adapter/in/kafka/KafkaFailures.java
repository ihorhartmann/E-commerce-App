package com.hartmann.adapter.in.kafka;

import com.hartmann.domain.exception.PaymentServiceException;

import static com.hartmann.domain.exception.Attributes.outermostOf;

public final class KafkaFailures {
    private KafkaFailures() {
    }

    /**
     * Whether a message whose handling failed with exception can't succeed on any later attempt, so it is
     * dead-lettered at once instead of retried: decided by the outermost PaymentServiceException in its cause
     * chain - the top of the chain is the listener container's own wrapper. A failure without one is retried.
     */
    public static boolean isPermanentFailure(Throwable exception) {
        return outermostOf(exception).map(PaymentServiceException::isPermanent).orElse(false);
    }
}
