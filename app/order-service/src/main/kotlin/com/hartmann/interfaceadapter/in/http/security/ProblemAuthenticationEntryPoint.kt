package com.hartmann.interfaceadapter.`in`.http.security

import com.hartmann.interfaceadapter.`in`.http.exception.InvalidTokenException
import com.hartmann.interfaceadapter.`in`.http.exception.MissingBearerTokenException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * Answers an unauthenticated request with a 401 like BearerTokenAuthenticationEntryPoint does - including its
 * WWW-Authenticate header - but with a ProblemDetail body written by GlobalExceptionHandler, like every other
 * error response. A request without any token reaches here as InsufficientAuthenticationException.
 */
class ProblemAuthenticationEntryPoint(
    private val handlerExceptionResolver: HandlerExceptionResolver,
) : AuthenticationEntryPoint {
    private val bearerTokenEntryPoint = BearerTokenAuthenticationEntryPoint()

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        bearerTokenEntryPoint.commence(request, response, authException)
        val exception = if (authException is InsufficientAuthenticationException) {
            MissingBearerTokenException(authException)
        } else {
            InvalidTokenException(authException)
        }
        handlerExceptionResolver.resolveException(request, response, null, exception)
    }
}
