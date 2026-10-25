package com.hartmann.interfaceadapter.out.http.common.dto

import org.springframework.web.client.HttpStatusCodeException

/**
 * The ProblemDetail body of an error response from a sibling service (inventory, payment) - only the
 * members this service branches on. HTTP clients map a failure by [code], never by the status alone:
 * one status can mean several things (e.g. a 409 from inventory). Every member is optional, so a
 * ProblemDetail without them still parses.
 */
data class ProblemResponse(
    val status: Int? = null,
    val code: String? = null,
    val retryable: Boolean = false,
)

/**
 * The ProblemDetail body of [exception]'s response, or null when the body isn't one. That is expected, not a
 * failure of its own: a proxy in front of the service answers with an HTML page, a 502 or 504 often has no body
 * at all. Without a code, the caller maps the response as an internal failure with [exception] as its cause, so
 * the parse failure is kept on [exception], as suppressed, and is logged with it once, in its stack trace.
 */
fun toProblemResponse(exception: HttpStatusCodeException): ProblemResponse? =
    try {
        exception.getResponseBodyAs(ProblemResponse::class.java)
    } catch (e: RuntimeException) {
        exception.addSuppressed(e)
        null
    }
