package com.hartmann.application.service.exception;

import com.hartmann.application.exception.ApplicationException;

/** The request's idempotency key already belongs to a refund for a different payment or amount. */
public class IdempotencyKeyReusedException extends ApplicationException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
