package com.hartmann.application.port.out.repository.exception;

import com.hartmann.application.exception.ApplicationException;

/** A row with the same idempotency key already exists, so nothing was created. */
public class DuplicateIdempotencyKeyException extends ApplicationException {
    public DuplicateIdempotencyKeyException(String message) {
        super(message);
    }
}
