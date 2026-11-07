package com.hartmann.infrastructure.observation;

import org.springframework.http.server.observation.OpenTelemetryServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Names an incoming request's span `POST /api/v1/payments`. Its timer is the metric OpenTelemetry's semantic
 * conventions define, `http.server.request.duration`, with their attribute names (`http.route`,
 * `http.response.status_code`, ...) - the same the gateway and the Go services record, instead of Spring's own
 * `http.server.requests`.
 */
public class HttpServerSpanNameConvention extends OpenTelemetryServerRequestObservationConvention {
    @Override
    public String getContextualName(ServerRequestObservationContext context) {
        var method = context.getCarrier() == null ? SpanNames.UNKNOWN_METHOD : context.getCarrier().getMethod();
        return SpanNames.httpSpanName(method, context.getPathPattern());
    }
}
