package com.hartmann.adapter.in.http.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/** Reported as 401. cause is the exception Spring Security rejected the request with. */
public class MissingBearerTokenException extends InvalidException {
    public MissingBearerTokenException(Throwable cause) {
        super(HttpErrorCode.MISSING_BEARER_TOKEN, "Missing Bearer access token", Map.of(), cause);
    }
}
