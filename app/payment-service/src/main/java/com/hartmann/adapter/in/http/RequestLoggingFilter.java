package com.hartmann.adapter.in.http;

import com.hartmann.adapter.common.tracing.CorrelationConst;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Centralizes inbound HTTP request logging so individual controllers don't each have to log
 * their own receipt/success pairs. Errors are logged separately by GlobalExceptionHandler.
 * <p>
 * The saga's correlation id a request brings in its header - a call from another of this system's services; the
 * gateway drops the header from a client's request - goes into the MDC for every line logged while it is handled. So
 * does one the request's service tags it with later (CorrelationTagger); either is removed once the request is done.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String HEALTH_PATH = "/health";

    // health checks and probes are polled every few seconds - a line each would bury the requests that matter
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var correlationId = request.getHeader(CorrelationConst.HTTP_HEADER);
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(CorrelationConst.CORRELATION_ID_KEY, correlationId);
        }
        try {
            LOG.atDebug().setMessage("received http request")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .log();

            filterChain.doFilter(request, response);

            if (response.getStatus() < 400) {
                LOG.atInfo().setMessage("successfully handled http request")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        .addKeyValue("status", response.getStatus())
                        .log();
            }
        } finally {
            MDC.remove(CorrelationConst.CORRELATION_ID_KEY);
        }
    }
}
