package com.hartmann.observation

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.web.server.ServerWebExchange

// Line comments in this package: the route patterns they quote end in a slash and two stars, which would open a
// nested block comment in Kotlin.

// OpenTelemetry's name for a request whose method isn't known.
const val UNKNOWN_METHOD = "HTTP"

// `{METHOD} {route}`, OpenTelemetry's HTTP span name: the method in upper case, the route as a template
// (`/api/v1/orders/**`, never the path of one request); the method alone when no template is known.
fun httpSpanName(method: String, route: String?): String = if (route.isNullOrEmpty()) method else "$method $route"

// The path pattern of the route a request was sent on, as declared in application.yml (`/api/v1/orders/**`) -
// a routed request has no handler of its own whose pattern Spring would know.
fun matchedRoutePath(attributes: Map<String, Any>): String? =
    attributes[ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ATTR] as? String

fun matchedRoutePath(exchange: ServerWebExchange): String? = matchedRoutePath(exchange.attributes)
