package com.hartmann.adapter.in.http.exception;

import com.hartmann.domain.exception.InvalidException;

public class MissingIdempotencyKeyException extends InvalidException {
    public MissingIdempotencyKeyException() {
        super(HttpErrorCode.MISSING_IDEMPOTENCY_KEY, "Missing Idempotency-Key header");
    }
}
