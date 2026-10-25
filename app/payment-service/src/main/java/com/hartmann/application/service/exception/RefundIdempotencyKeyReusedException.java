package com.hartmann.application.service.exception;

import com.hartmann.domain.exception.ConflictException;

import java.util.Map;
import java.util.UUID;

/** The request's idempotency key already belongs to a refund for a different payment or amount. */
public class RefundIdempotencyKeyReusedException extends ConflictException {
    public RefundIdempotencyKeyReusedException(UUID idempotencyKey, long refundId) {
        super(ServiceErrorCode.REFUND_IDEMPOTENCY_KEY_REUSED, "Refund idempotency key reused",
                Map.of("idempotencyKey", idempotencyKey, "refundId", refundId));
    }
}
