package com.hartmann.infrastructure.observation;

import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

// Names an incoming request's span `POST /api/v1/payments` instead of Spring's `http post /api/v1/payments`.
public class HttpServerSpanNameConvention extends DefaultServerRequestObservationConvention {
    @Override
    public String getContextualName(ServerRequestObservationContext context) {
        var method = context.getCarrier() == null ? SpanNames.UNKNOWN_METHOD : context.getCarrier().getMethod();
        return SpanNames.httpSpanName(method, context.getPathPattern());
    }
}
