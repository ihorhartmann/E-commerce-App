package com.hartmann.adapter.in.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Centralizes inbound HTTP request logging so individual controllers don't each have to log
 * their own receipt/success pairs. Errors are logged separately by GlobalExceptionHandler.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        LOG.debug("received http request method={} path={}", request.getMethod(), request.getRequestURI());

        filterChain.doFilter(request, response);

        if (response.getStatus() < 400) {
            LOG.info(
                    "successfully handled http request method={} path={} status={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus()
            );
        }
    }
}
