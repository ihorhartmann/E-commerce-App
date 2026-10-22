package com.hartmann.interfaceadapter.`in`.http.exception

import com.hartmann.entities.exception.InvalidException

/**
 * The caller has no role that allows the endpoint. Reported as 403. [cause] is the AccessDeniedException
 * Spring Security rejected the request with.
 */
class AccessForbiddenException(cause: Throwable) :
    InvalidException(HttpErrorCode.ACCESS_DENIED, "Access denied", cause = cause)
