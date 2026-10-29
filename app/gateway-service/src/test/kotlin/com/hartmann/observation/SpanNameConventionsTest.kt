package com.hartmann.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.mock.web.server.MockServerWebExchange

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SpanNameConventionsTest {

    @Test
    fun `getContextualName - names a routed incoming request by its method and the route's path pattern`() {
        val routePath = "/api/v1/orders/**"
        val context = ServerRequestObservationContext(
            MockServerHttpRequest.post("/api/v1/orders").build(),
            MockServerHttpResponse(),
            mapOf(ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ATTR to routePath),
        )

        assertEquals("POST $routePath", HttpServerSpanNameConvention().getContextualName(context))
    }

    @Test
    fun `getContextualName - names a request to the gateway's own endpoint by its handler's pattern`() {
        val context = ServerRequestObservationContext(MockServerHttpRequest.get("/api/v1/me").build(), MockServerHttpResponse(), emptyMap())
        context.pathPattern = "/api/v1/me"

        assertEquals("GET /api/v1/me", HttpServerSpanNameConvention().getContextualName(context))
    }

    @Test
    fun `getContextualName - names a request to one of Spring Security's own endpoints by its method and path`() {
        val endpoints = listOf(
            MockServerHttpRequest.get("/oauth2/authorization/keycloak").build() to "GET /oauth2/authorization/keycloak",
            MockServerHttpRequest.get("/login/oauth2/code/keycloak?code=abc&state=xyz").build() to "GET /login/oauth2/code/keycloak",
            MockServerHttpRequest.post("/logout").build() to "POST /logout",
            MockServerHttpRequest.get("/login").build() to "GET /login",
            MockServerHttpRequest.get("/logout").build() to "GET /logout",
        )

        endpoints.forEach { (request, expected) ->
            val context = ServerRequestObservationContext(request, MockServerHttpResponse(), emptyMap())
            assertEquals(expected, HttpServerSpanNameConvention().getContextualName(context))
        }
    }

    @Test
    fun `getContextualName - names a Spring Security path requested with another method by its method alone`() {
        val context = ServerRequestObservationContext(MockServerHttpRequest.post("/login").build(), MockServerHttpResponse(), emptyMap())

        assertEquals("POST", HttpServerSpanNameConvention().getContextualName(context))
    }

    @Test
    fun `getContextualName - names a request without a route or a handler by its method alone`() {
        val context = ServerRequestObservationContext(MockServerHttpRequest.get("/nowhere").build(), MockServerHttpResponse(), emptyMap())

        assertEquals("GET", HttpServerSpanNameConvention().getContextualName(context))
    }

    @Test
    fun `getContextualName - names the request sent on to a service by its method and the route's path pattern`() {
        val routePath = "/api/v1/shipments/**"
        val request = MockServerHttpRequest.post("/api/v1/shipments/7/pickup").build()
        val exchange = MockServerWebExchange.from(request)
        exchange.attributes[ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ATTR] = routePath

        val actual = RoutedRequestSpanNameConvention().getContextualName(GatewayContext(HttpHeaders(), request, exchange))

        assertEquals("POST $routePath", actual)
    }
}
