package com.hartmann.interfaceadapter.`in`.http.exception

import com.hartmann.entities.exception.InvalidException

/** Reported as 401. [cause] is the exception Spring Security rejected the token with - expired, wrong issuer, ... */
class InvalidTokenException(cause: Throwable) :
    InvalidException(HttpErrorCode.INVALID_TOKEN, "Invalid access token", cause = cause)
