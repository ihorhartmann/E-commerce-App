package com.hartmann.observation

import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext

// Names an incoming request's span `POST /api/v1/orders/**` - the route it is sent on - instead of Spring's bare
// `http post`; the gateway's own endpoints by their handler's pattern (`GET /api/v1/me`), and Spring Security's by
// their path (`GET /login/oauth2/code/keycloak`).
class HttpServerSpanNameConvention : DefaultServerRequestObservationConvention() {
    override fun getContextualName(context: ServerRequestObservationContext): String {
        val method = context.carrier?.method?.name() ?: UNKNOWN_METHOD
        val pattern = context.pathPattern ?: matchedRoutePath(context.attributes)
        if (pattern != null) {
            return httpSpanName(method, pattern)
        }
        return securityEndpointSpanName(method, context.carrier?.path?.value()) ?: httpSpanName(method, null)
    }
}
