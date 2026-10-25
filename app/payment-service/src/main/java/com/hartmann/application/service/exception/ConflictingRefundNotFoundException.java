package com.hartmann.application.service.exception;

import com.hartmann.domain.exception.PaymentServiceException;

import java.util.Map;
import java.util.UUID;

/**
 * Inserting a refund conflicted on its idempotency key, yet no refund with that key can be found - the database
 * contradicts itself, since the conflicting row is committed by then and refunds are never deleted. Not a request
 * to reject, and no refund is there to call inconsistent.
 */
public class ConflictingRefundNotFoundException extends PaymentServiceException {
    public ConflictingRefundNotFoundException(UUID idempotencyKey) {
        super(ServiceErrorCode.CONFLICTING_REFUND_NOT_FOUND, "Refund conflicted on insert but can't be found", null,
                Map.of("idempotencyKey", idempotencyKey), null);
    }
}
