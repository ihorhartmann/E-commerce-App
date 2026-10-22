package com.hartmann.interfaceadapter.`in`.http.exception

import com.hartmann.entities.exception.ErrorCode

/**
 * Codes of failures the HTTP layer itself detects - parsing, routing, authentication, idempotency -
 * rather than a use case. Most are given to Spring's own exceptions by GlobalExceptionHandler; the
 * rest have an exception of their own here.
 */
enum class HttpErrorCode(override val title: String) : ErrorCode {
    INTERNAL_ERROR("Internal server error"),
    INVALID_REQUEST("Invalid request"),
    VALIDATION_FAILED("Request validation failed"),
    MALFORMED_REQUEST_BODY("Malformed request body"),
    INVALID_PARAMETER("Invalid request parameter"),
    MISSING_HEADER("Missing request header"),
    ROUTE_NOT_FOUND("No such endpoint"),
    METHOD_NOT_ALLOWED("Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE("Unsupported media type"),
    MISSING_BEARER_TOKEN("Missing Bearer access token"),
    INVALID_TOKEN("Invalid access token"),
    ACCESS_DENIED("Access denied"),
    MISSING_IDEMPOTENCY_KEY("Missing Idempotency-Key header"),
    IDEMPOTENCY_KEY_REUSED("Idempotency-Key was already used with a different request"),
    REQUEST_IN_PROGRESS("A request with this Idempotency-Key is still in progress"),
}
