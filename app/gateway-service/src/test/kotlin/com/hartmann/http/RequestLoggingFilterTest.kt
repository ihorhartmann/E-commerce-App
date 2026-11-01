package com.hartmann.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RequestLoggingFilterTest {

    private val filter = RequestLoggingFilter()
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(RequestLoggingFilter::class.java) as Logger

    @BeforeEach
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        logger.level = null
    }

    private fun respondingWith(status: HttpStatus) = WebFilterChain { exchange ->
        exchange.response.statusCode = status
        Mono.empty()
    }

    private fun messages() = appender.list.map { it.level to it.message }

    @Test
    fun `filter - logs the request on its way in and a successful one on its way out, at debug`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products"))

        filter.filter(exchange, respondingWith(HttpStatus.OK)).block()

        val expected = listOf(Level.DEBUG to "received http request", Level.DEBUG to "successfully handled http request")
        assertEquals(expected, messages())
    }

    @Test
    fun `filter - logs a 5xx the service answered with at debug only`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"))

        filter.filter(exchange, respondingWith(HttpStatus.INTERNAL_SERVER_ERROR)).block()

        val expected = listOf(Level.DEBUG to "received http request", Level.DEBUG to "service responded with an error")
        assertEquals(expected, messages())
    }

    @Test
    fun `filter - logs nothing on the way out of a 4xx`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"))

        filter.filter(exchange, respondingWith(HttpStatus.NOT_FOUND)).block()

        assertEquals(listOf(Level.DEBUG to "received http request"), messages())
    }
}
