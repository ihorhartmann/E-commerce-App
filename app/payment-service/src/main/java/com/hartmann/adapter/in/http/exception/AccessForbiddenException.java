package com.hartmann.adapter.in.http.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/**
 * The caller has no role that allows the endpoint. Reported as 403. cause is the AccessDeniedException
 * Spring Security rejected the request with.
 */
public class AccessForbiddenException extends InvalidException {
    public AccessForbiddenException(Throwable cause) {
        super(HttpErrorCode.ACCESS_DENIED, "Access denied", Map.of(), cause);
    }
}
