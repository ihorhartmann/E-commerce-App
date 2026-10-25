package com.hartmann.interfaceadapter.`in`.http.security

import com.hartmann.interfaceadapter.`in`.http.exception.AccessForbiddenException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * Answers a request whose caller lacks the role an endpoint needs with a 403 like BearerTokenAccessDeniedHandler
 * does - including its WWW-Authenticate header - but with a ProblemDetail body written by GlobalExceptionHandler,
 * like every other error response.
 */
class ProblemAccessDeniedHandler(
    private val handlerExceptionResolver: HandlerExceptionResolver,
) : AccessDeniedHandler {
    private val bearerTokenAccessDeniedHandler = BearerTokenAccessDeniedHandler()

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        bearerTokenAccessDeniedHandler.handle(request, response, accessDeniedException)
        handlerExceptionResolver.resolveException(request, response, null, AccessForbiddenException(accessDeniedException))
    }
}
