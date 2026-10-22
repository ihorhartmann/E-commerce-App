package com.hartmann.interfaceadapter.`in`.http.exception

import com.hartmann.entities.exception.InvalidException

/** Reported as 401. [cause] is the exception Spring Security rejected the request with. */
class MissingBearerTokenException(cause: Throwable) :
    InvalidException(HttpErrorCode.MISSING_BEARER_TOKEN, "Missing Bearer access token", cause = cause)
