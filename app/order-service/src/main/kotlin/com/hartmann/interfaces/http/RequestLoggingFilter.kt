package com.hartmann.interfaces.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Centralizes inbound HTTP request logging so individual controllers don't each have to log
 * their own receipt/success pairs. Errors are logged separately by GlobalExceptionHandler.
 */
@Component
class RequestLoggingFilter : OncePerRequestFilter() {
    companion object {
        private val LOG = LoggerFactory.getLogger(RequestLoggingFilter::class.java)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        LOG.debug("received http request method={} path={}", request.method, request.requestURI)

        filterChain.doFilter(request, response)

        if (response.status < 400) {
            LOG.info(
                "successfully handled http request method={} path={} status={}",
                request.method, request.requestURI, response.status
            )
        }
    }
}
