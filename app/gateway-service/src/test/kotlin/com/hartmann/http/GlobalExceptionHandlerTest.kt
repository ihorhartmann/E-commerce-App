package com.hartmann.http

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ConnectException

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler(ServerCodecConfigurer.create())

    private fun exchange(path: String) = MockServerWebExchange.from(MockServerHttpRequest.get(path))

    private fun body(exchange: MockServerWebExchange): JsonNode =
        JsonMapper.builder().build().readTree(exchange.response.bodyAsString.block())

    @Test
    fun `handle - answers a route that doesn't exist with a 404 ProblemDetail`() {
        val path = "/api/v1/unknown"
        val exchange = exchange(path)

        handler.handle(exchange, ResponseStatusException(HttpStatus.NOT_FOUND)).block()

        assertEquals(HttpStatus.NOT_FOUND, exchange.response.statusCode)
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.response.headers.contentType)
        val body = body(exchange)
        val code = ErrorCode.ROUTE_NOT_FOUND.code
        assertEquals("urn:ecommerce:error:$code", body["type"].asString())
        assertEquals(ErrorCode.ROUTE_NOT_FOUND.title, body["title"].asString())
        assertEquals(HttpStatus.NOT_FOUND.value(), body["status"].asInt())
        assertEquals(path, body["instance"].asString())
        assertEquals(code, body["code"].asString())
        assertEquals(false, body["retryable"].asBoolean())
        assertNull(exchange.response.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }

    @Test
    fun `handle - answers a service it can't connect to with a retryable 503`() {
        val exchange = exchange("/api/v1/orders")

        handler.handle(exchange, RuntimeException("routing failed", ConnectException("Connection refused"))).block()

        val status = HttpStatus.SERVICE_UNAVAILABLE
        assertEquals(status, exchange.response.statusCode)
        val body = body(exchange)
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE.code, body["code"].asString())
        assertEquals(status.value(), body["status"].asInt())
        assertEquals(true, body["retryable"].asBoolean())
        assertEquals(GlobalExceptionHandler.RETRY_AFTER_SECONDS.toString(), exchange.response.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }

    @Test
    fun `handle - answers a service that doesn't respond in time with a retryable 504`() {
        val exchange = exchange("/api/v1/orders")

        handler.handle(exchange, ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT)).block()

        val status = HttpStatus.GATEWAY_TIMEOUT
        assertEquals(status, exchange.response.statusCode)
        val body = body(exchange)
        assertEquals(ErrorCode.SERVICE_TIMEOUT.code, body["code"].asString())
        assertEquals(status.value(), body["status"].asInt())
        assertEquals(true, body["retryable"].asBoolean())
    }

    @Test
    fun `handle - answers a request without credentials with a 401`() {
        val exchange = exchange("/api/v1/orders")

        handler.handle(exchange, AuthenticationCredentialsNotFoundException("Not authenticated")).block()

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
        assertEquals(ErrorCode.UNAUTHENTICATED.code, body(exchange)["code"].asString())
    }

    @Test
    fun `handle - answers an invalid Bearer token with a 401 of its own code`() {
        val exchange = exchange("/api/v1/orders")

        handler.handle(exchange, InvalidBearerTokenException("Jwt expired")).block()

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
        assertEquals(ErrorCode.INVALID_TOKEN.code, body(exchange)["code"].asString())
    }

    @Test
    fun `handle - answers a request that fails the CSRF check with a 403`() {
        val exchange = exchange("/api/v1/orders")

        handler.handle(exchange, AccessDeniedException("Invalid CSRF Token")).block()

        assertEquals(HttpStatus.FORBIDDEN, exchange.response.statusCode)
        assertEquals(ErrorCode.ACCESS_DENIED.code, body(exchange)["code"].asString())
    }

    @Test
    fun `handle - answers any other failure with a 500 that doesn't show its message`() {
        val exchange = exchange("/api/v1/me")

        handler.handle(exchange, IllegalStateException("token refresh failed")).block()

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.response.statusCode)
        val body = body(exchange)
        assertEquals(ErrorCode.INTERNAL_ERROR.code, body["code"].asString())
        assertNull(body["detail"])
    }

    @Test
    fun `handle - passes the failure on when the response has already been sent`() {
        val exchange = exchange("/api/v1/orders")
        exchange.response.setComplete().block()
        val exception = IllegalStateException("failed mid-response")

        val thrown = assertThrows<IllegalStateException> { handler.handle(exchange, exception).block() }

        assertSame(exception, thrown)
    }

    @Test
    fun `handle - counts the failure by its code`() {
        val registry = SimpleMeterRegistry()
        Metrics.addRegistry(registry)

        try {
            handler.handle(exchange("/api/v1/orders"), RuntimeException("routing failed", ConnectException("Connection refused"))).block()
        } finally {
            Metrics.removeRegistry(registry)
        }

        val failures = registry.find(GlobalExceptionHandler.FAILURES)
            .tags("error.code", ErrorCode.SERVICE_UNAVAILABLE.code, "transport", "http")
            .counter()
        assertEquals(1.0, failures?.count())
    }

    @Test
    fun `handle - counts nothing when the response has already been sent`() {
        val exchange = exchange("/api/v1/orders")
        exchange.response.setComplete().block()
        val registry = SimpleMeterRegistry()
        Metrics.addRegistry(registry)

        try {
            assertThrows<IllegalStateException> { handler.handle(exchange, IllegalStateException("failed mid-response")).block() }
        } finally {
            Metrics.removeRegistry(registry)
        }

        // the meters of the global registry outlive a test, and come into each new registry, with nothing counted yet
        assertEquals(0.0, registry.find(GlobalExceptionHandler.FAILURES).counters().sumOf { it.count() })
    }
}
