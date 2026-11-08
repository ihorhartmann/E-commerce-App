package com.hartmann.observation

import io.micrometer.common.KeyValue
import io.micrometer.common.KeyValues
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext

// Names an incoming request's span `POST /api/v1/orders/**` - the route it is sent on - instead of Spring's bare
// `http post`; the gateway's own endpoints by their handler's pattern (`GET /api/v1/me`), and Spring Security's by
// their path (`GET /login/oauth2/code/keycloak`).
//
// Its timer is the metric OpenTelemetry's semantic conventions define, `http.server.request.duration`, with their
// attribute names - the same the services record (Spring MVC's OpenTelemetryServerRequestObservationConvention has no
// WebFlux counterpart): Spring's own `http.server.requests` with `method`/`uri`/`status` couldn't be summed with them.
class HttpServerSpanNameConvention : DefaultServerRequestObservationConvention() {
    private companion object {
        const val METRIC_NAME = "http.server.request.duration"
        const val UNKNOWN = "UNKNOWN"
        const val NOT_FOUND = "NOT_FOUND"
    }

    override fun getName(): String = METRIC_NAME

    override fun getContextualName(context: ServerRequestObservationContext): String {
        val method = context.carrier?.method?.name() ?: UNKNOWN_METHOD
        val pattern = routeOf(context)
        if (pattern != null) {
            return httpSpanName(method, pattern)
        }
        return securityEndpointSpanName(method, context.carrier?.path?.value()) ?: httpSpanName(method, null)
    }

    override fun getLowCardinalityKeyValues(context: ServerRequestObservationContext): KeyValues {
        val status = context.response?.statusCode
        return KeyValues.of(
            KeyValue.of("http.request.method", context.carrier?.method?.name() ?: UNKNOWN_METHOD),
            KeyValue.of("http.response.status_code", status?.value()?.toString() ?: UNKNOWN),
            KeyValue.of("http.route", routeOf(context) ?: if (status == HttpStatus.NOT_FOUND) NOT_FOUND else UNKNOWN),
            KeyValue.of("error.type", context.error?.javaClass?.name ?: KeyValue.NONE_VALUE),
        )
    }

    // a handler's own pattern, or the pattern of the route a request was sent on
    private fun routeOf(context: ServerRequestObservationContext): String? =
        context.pathPattern ?: matchedRoutePath(context.attributes)
}
