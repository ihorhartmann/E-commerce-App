package com.hartmann.http

import org.springframework.http.HttpStatus

/**
 * The failures the gateway itself answers - the services answer everything else. Each is a ProblemDetail of its own
 * code (`GATEWAY.` + its name), title and status, the same shape as the services' error responses.
 */
enum class ErrorCode(val status: HttpStatus, val title: String, val retryable: Boolean = false) {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid access token"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "No such endpoint"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", retryable = true),
    SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Service did not respond in time", retryable = true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    val code: String
        get() = "GATEWAY.$name"
}
