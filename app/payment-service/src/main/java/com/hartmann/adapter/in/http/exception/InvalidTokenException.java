package com.hartmann.adapter.in.http.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/** Reported as 401. cause is the exception Spring Security rejected the token with - expired, wrong issuer, ... */
public class InvalidTokenException extends InvalidException {
    public InvalidTokenException(Throwable cause) {
        super(HttpErrorCode.INVALID_TOKEN, "Invalid access token", Map.of(), cause);
    }
}
