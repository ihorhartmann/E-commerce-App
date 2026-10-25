package com.hartmann.adapter.in.http.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/** Reported as 422, not 400 - the status the IETF Idempotency-Key draft gives it. */
public class IdempotencyKeyReusedException extends InvalidException {
    public IdempotencyKeyReusedException(String idempotencyKey) {
        super(HttpErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key reused with a different request",
                Map.of("idempotencyKey", idempotencyKey));
    }
}
