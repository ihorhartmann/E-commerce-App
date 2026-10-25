package com.hartmann.interfaceadapter.`in`.http.exception

import com.hartmann.entities.exception.InvalidException

class MissingIdempotencyKeyException :
    InvalidException(HttpErrorCode.MISSING_IDEMPOTENCY_KEY, "Missing Idempotency-Key header")
