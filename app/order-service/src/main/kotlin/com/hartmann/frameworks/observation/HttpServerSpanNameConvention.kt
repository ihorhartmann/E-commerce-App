package com.hartmann.frameworks.observation

import org.springframework.http.server.observation.OpenTelemetryServerRequestObservationConvention
import org.springframework.http.server.observation.ServerRequestObservationContext

/**
 * Names an incoming request's span `POST /api/v1/orders`. Its timer is the metric OpenTelemetry's semantic conventions
 * define, `http.server.request.duration`, with their attribute names (`http.route`, `http.response.status_code`, ...) -
 * the same the gateway and the Go services record, instead of Spring's own `http.server.requests`.
 */
class HttpServerSpanNameConvention : OpenTelemetryServerRequestObservationConvention() {
    override fun getContextualName(context: ServerRequestObservationContext): String =
        httpSpanName(context.carrier?.method ?: UNKNOWN_METHOD, context.pathPattern)
}
