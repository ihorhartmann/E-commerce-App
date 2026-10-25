package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.PaymentServiceException;

import java.util.Map;

/**
 * A stored refund breaks the refund's own rules - corrupted data, not a request to reject. Thrown with the broken
 * rule by Refund.createExisting, or with the exception the refund rejected the stored data with as the cause by
 * the repository's mapper - an exception that on its own would read as the caller's fault.
 */
public class InconsistentRefundException extends PaymentServiceException {
    public InconsistentRefundException(long refundId, String rule) {
        super(RefundErrorCode.INCONSISTENT_REFUND, "Stored refund is inconsistent", null,
                Map.of("refundId", refundId, "rule", rule), null);
    }

    public InconsistentRefundException(long refundId, Throwable cause) {
        super(RefundErrorCode.INCONSISTENT_REFUND, "Stored refund is inconsistent", null,
                Map.of("refundId", refundId), cause);
    }
}
