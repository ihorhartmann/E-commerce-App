package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.usecase.port.common.tracing.CorrelationConst
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Centralizes inbound HTTP request logging so individual controllers don't each have to log
 * their own receipt/success pairs. Errors are logged separately by GlobalExceptionHandler.
 *
 * The saga's correlation id a request brings in its header - a call from another of this system's services; the
 * gateway drops the header from a client's request - goes into the MDC for every line logged while it is handled. So
 * does one the request's use case tags it with later (CorrelationTagger); either is removed once the request is done.
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
        request.getHeader(CorrelationConst.HTTP_HEADER)?.takeIf { it.isNotBlank() }?.let {
            MDC.put(CorrelationConst.CORRELATION_ID_KEY, it)
        }
        try {
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
        } finally {
            MDC.remove(CorrelationConst.CORRELATION_ID_KEY)
        }
    }
}
