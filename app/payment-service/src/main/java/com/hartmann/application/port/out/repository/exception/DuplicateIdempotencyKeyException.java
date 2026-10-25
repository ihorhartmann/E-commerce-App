package com.hartmann.application.port.out.repository.exception;

import com.hartmann.domain.exception.ConflictException;

import java.util.Map;
import java.util.UUID;

/** A refund with the same idempotency key already exists, so nothing was created. */
public class DuplicateIdempotencyKeyException extends ConflictException {
    public DuplicateIdempotencyKeyException(UUID idempotencyKey) {
        super(RepositoryErrorCode.DUPLICATE_REFUND_IDEMPOTENCY_KEY, "Duplicate refund idempotency key",
                Map.of("idempotencyKey", idempotencyKey));
    }
}
