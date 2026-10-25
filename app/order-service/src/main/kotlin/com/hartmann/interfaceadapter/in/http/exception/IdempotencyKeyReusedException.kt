package com.hartmann.interfaceadapter.`in`.http.exception

import com.hartmann.entities.exception.InvalidException

/** Reported as 422, not 400 - the status the IETF Idempotency-Key draft gives it. */
class IdempotencyKeyReusedException(idempotencyKey: String) : InvalidException(
    HttpErrorCode.IDEMPOTENCY_KEY_REUSED,
    "Idempotency-Key reused with a different request",
    mapOf("idempotencyKey" to idempotencyKey),
)
