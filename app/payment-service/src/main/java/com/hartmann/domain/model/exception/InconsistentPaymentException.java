package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.PaymentServiceException;

import java.util.Map;

/**
 * A stored payment breaks the payment's own rules - corrupted data, not a request to reject. Thrown with the broken
 * rule by Payment.createExisting, or with the exception the payment rejected the stored data with as the cause by
 * the repository's mapper - an exception that on its own would read as the caller's fault.
 */
public class InconsistentPaymentException extends PaymentServiceException {
    public InconsistentPaymentException(long paymentId, String rule) {
        super(PaymentErrorCode.INCONSISTENT_PAYMENT, "Stored payment is inconsistent", null,
                Map.of("paymentId", paymentId, "rule", rule), null);
    }

    public InconsistentPaymentException(long paymentId, Throwable cause) {
        super(PaymentErrorCode.INCONSISTENT_PAYMENT, "Stored payment is inconsistent", null,
                Map.of("paymentId", paymentId), cause);
    }
}
