package com.hartmann.adapter.in.http.security;

import com.hartmann.adapter.in.http.exception.InvalidTokenException;
import com.hartmann.adapter.in.http.exception.MissingBearerTokenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers an unauthenticated request with a 401 like BearerTokenAuthenticationEntryPoint does - including its
 * WWW-Authenticate header - but with a ProblemDetail body written by GlobalExceptionHandler, like every other
 * error response. A request without any token reaches here as InsufficientAuthenticationException.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final BearerTokenAuthenticationEntryPoint bearerTokenEntryPoint = new BearerTokenAuthenticationEntryPoint();

    public ProblemAuthenticationEntryPoint(HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) {
        bearerTokenEntryPoint.commence(request, response, authException);
        Exception exception = authException instanceof InsufficientAuthenticationException
                ? new MissingBearerTokenException(authException)
                : new InvalidTokenException(authException);
        handlerExceptionResolver.resolveException(request, response, null, exception);
    }
}
