package com.hartmann.adapter.in.http.exception;

import com.hartmann.domain.exception.TransientException;

import java.util.Map;

/**
 * Transient: repeating the same request once the first one has finished gets its response. Reported as 409, not
 * 503 - the status the IETF Idempotency-Key draft gives it.
 */
public class RequestInProgressException extends TransientException {
    public RequestInProgressException(String idempotencyKey) {
        super(HttpErrorCode.REQUEST_IN_PROGRESS, "Request with this Idempotency-Key in progress",
                Map.of("idempotencyKey", idempotencyKey));
    }
}
