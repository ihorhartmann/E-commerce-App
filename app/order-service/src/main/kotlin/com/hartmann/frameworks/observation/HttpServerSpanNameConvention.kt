package com.hartmann.frameworks.observation

import org.springframework.http.server.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.observation.ServerRequestObservationContext

/** Names an incoming request's span `POST /api/v1/orders` instead of Spring's `http post /api/v1/orders`. */
class HttpServerSpanNameConvention : DefaultServerRequestObservationConvention() {
    override fun getContextualName(context: ServerRequestObservationContext): String =
        httpSpanName(context.carrier?.method ?: UNKNOWN_METHOD, context.pathPattern)
}
