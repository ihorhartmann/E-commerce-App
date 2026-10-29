package com.hartmann.frameworks.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import org.springframework.http.client.observation.ClientRequestObservationContext
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.URI

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class HttpSpanNameConventionsTest {

    @Test
    fun `getContextualName - names an incoming request by its method and route template`() {
        val context = ServerRequestObservationContext(MockHttpServletRequest("GET", "/api/v1/orders/42"), MockHttpServletResponse())
        context.pathPattern = "/api/v1/orders/{orderId}"

        assertEquals("GET /api/v1/orders/{orderId}", HttpServerSpanNameConvention().getContextualName(context))
    }

    @Test
    fun `getContextualName - names an outgoing call by its method and URI template`() {
        val context = ClientRequestObservationContext(MockClientHttpRequest(HttpMethod.POST, URI.create("http://payment-service:3003/api/v1/payments")))
        context.uriTemplate = "/api/v1/payments"

        assertEquals("POST /api/v1/payments", HttpClientSpanNameConvention().getContextualName(context))
    }
}
