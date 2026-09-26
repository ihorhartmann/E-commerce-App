package com.hartmann.interfaceadapter.out.http

import com.hartmann.entities.model.OrderItem
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import com.hartmann.usecase.port.out.internal.exception.InventoryUnavailableException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import io.mockk.Runs
import io.mockk.andThenJust
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.net.ConnectException
import java.net.http.HttpTimeoutException
import java.time.Duration

private const val MAX_ATTEMPTS = 3
private val BACKOFF = Duration.ofMillis(1)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class Resilience4jInventoryClientTest {

    private val delegate = mockk<InventoryClient>()
    private val circuitBreaker = CircuitBreaker.of("inventory-service", Resilience4jInventoryClient.circuitBreakerConfig())
    private val client = Resilience4jInventoryClient(
        delegate,
        Retry.of("inventory-service", connectFailureRetryConfig(MAX_ATTEMPTS, BACKOFF)),
        circuitBreaker,
    )

    @Test
    fun `reserve - delegates once when the call succeeds`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        every { delegate.reserve(orderId, items) } just Runs

        client.reserve(orderId, items)

        verify(exactly = 1) { delegate.reserve(orderId, items) }
    }

    @Test
    fun `reserve - retries a connect failure and succeeds on the next attempt`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        every { delegate.reserve(orderId, items) } throws
            ResourceAccessException("I/O error", ConnectException("Connection refused")) andThenJust Runs

        client.reserve(orderId, items)

        verify(exactly = 2) { delegate.reserve(orderId, items) }
    }

    @Test
    fun `reserve - throws InventoryUnavailableException once every attempt fails to connect`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        every { delegate.reserve(orderId, items) } throws
            ResourceAccessException("I/O error", ConnectException("Connection refused"))

        assertThrows<InventoryUnavailableException> { client.reserve(orderId, items) }

        verify(exactly = MAX_ATTEMPTS) { delegate.reserve(orderId, items) }
    }

    @Test
    fun `reserve - does not retry a read timeout and throws InventoryUnavailableException`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        every { delegate.reserve(orderId, items) } throws
            ResourceAccessException("I/O error", HttpTimeoutException("request timed out"))

        assertThrows<InventoryUnavailableException> { client.reserve(orderId, items) }

        verify(exactly = 1) { delegate.reserve(orderId, items) }
    }

    @Test
    fun `reserve - does not retry a server error and rethrows it`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        every { delegate.reserve(orderId, items) } throws
            HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)

        assertThrows<HttpServerErrorException> { client.reserve(orderId, items) }

        verify(exactly = 1) { delegate.reserve(orderId, items) }
    }

    @Test
    fun `reserve - does not retry insufficient stock and rethrows it`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        every { delegate.reserve(orderId, items) } throws InsufficientStockException("no stock")

        assertThrows<InsufficientStockException> { client.reserve(orderId, items) }

        verify(exactly = 1) { delegate.reserve(orderId, items) }
    }

    @Test
    fun `reserve - throws InventoryUnavailableException without calling the delegate while the circuit breaker is open`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        circuitBreaker.transitionToOpenState()

        assertThrows<InventoryUnavailableException> { client.reserve(orderId, items) }

        verify(exactly = 0) { delegate.reserve(any(), any()) }
    }

    @Test
    fun `reserve - opens the circuit breaker once enough calls fail with a server error`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        val failedCalls = 10
        every { delegate.reserve(orderId, items) } throws
            HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)

        repeat(failedCalls) { assertThrows<HttpServerErrorException> { client.reserve(orderId, items) } }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.state)
        assertThrows<InventoryUnavailableException> { client.reserve(orderId, items) }
        verify(exactly = failedCalls) { delegate.reserve(orderId, items) }
    }

    @Test
    fun `reserve - keeps the circuit breaker closed when calls are rejected for insufficient stock`() {
        val orderId = 1L
        val items = emptyList<OrderItem>()
        val rejectedCalls = 10
        every { delegate.reserve(orderId, items) } throws InsufficientStockException("no stock")

        repeat(rejectedCalls) { assertThrows<InsufficientStockException> { client.reserve(orderId, items) } }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
        verify(exactly = rejectedCalls) { delegate.reserve(orderId, items) }
    }
}
