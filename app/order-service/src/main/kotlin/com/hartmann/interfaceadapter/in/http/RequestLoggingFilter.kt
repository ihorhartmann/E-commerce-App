package com.hartmann.interfaceadapter.`in`.http

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
        private const val HEALTH_PATH = "/health"
    }

    // health checks and probes are polled every few seconds - a line each would bury the requests that matter
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI.startsWith(HEALTH_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        LOG.atDebug().setMessage("received http request")
            .addKeyValue("method", request.method)
            .addKeyValue("path", request.requestURI)
            .log()

        filterChain.doFilter(request, response)

        if (response.status < 400) {
            LOG.atInfo().setMessage("successfully handled http request")
                .addKeyValue("method", request.method)
                .addKeyValue("path", request.requestURI)
                .addKeyValue("status", response.status)
                .log()
        }
    }
}
