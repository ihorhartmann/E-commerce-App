package com.hartmann.observation

import org.springframework.cloud.gateway.filter.headers.observation.DefaultGatewayObservationConvention
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext

// Names the span of the request the gateway sends on to a service `POST /api/v1/orders/**` - the route - instead of
// Spring Cloud Gateway's `HTTP POST`.
class RoutedRequestSpanNameConvention : DefaultGatewayObservationConvention() {
    override fun getContextualName(context: GatewayContext): String =
        httpSpanName(context.request.method.name(), matchedRoutePath(context.serverWebExchange))
}
