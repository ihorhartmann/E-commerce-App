package com.hartmann.interfaceadapter.`in`.http.exception

import com.hartmann.entities.exception.TransientException

/**
 * Transient: repeating the same request once the first one has finished gets its response. Reported as 409, not
 * 503 - the status the IETF Idempotency-Key draft gives it.
 */
class RequestInProgressException(idempotencyKey: String) : TransientException(
    HttpErrorCode.REQUEST_IN_PROGRESS,
    "Request with this Idempotency-Key in progress",
    mapOf("idempotencyKey" to idempotencyKey),
)
