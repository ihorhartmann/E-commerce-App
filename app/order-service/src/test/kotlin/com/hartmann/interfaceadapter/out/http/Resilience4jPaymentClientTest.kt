package com.hartmann.interfaceadapter.out.http

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
import com.hartmann.usecase.port.out.internal.exception.PaymentGatewayException
import com.hartmann.usecase.port.out.internal.exception.PaymentGatewayUnavailableException
import com.hartmann.usecase.port.out.internal.exception.PaymentServiceUnavailableException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.http.HttpTimeoutException
import java.time.Duration

private const val MAX_ATTEMPTS = 3
private val BACKOFF = Duration.ofMillis(1)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class Resilience4jPaymentClientTest {

    private val delegate = mockk<PaymentClient>()
    private val circuitBreaker = CircuitBreaker.of("payment-service", Resilience4jPaymentClient.circuitBreakerConfig())
    private val client = Resilience4jPaymentClient(
        delegate,
        Retry.of("payment-service", connectFailureRetryConfig(MAX_ATTEMPTS, BACKOFF)),
        circuitBreaker,
    )

    @Test
    fun `submitPayment - returns the delegate's result when the call succeeds`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        val expected = SubmitPaymentResult(10L, "secret_123")
        every { delegate.submitPayment(orderId, amount) } returns expected

        val actual = client.submitPayment(orderId, amount)

        assertEquals(expected, actual)
        verify(exactly = 1) { delegate.submitPayment(orderId, amount) }
    }

    @Test
    fun `submitPayment - retries a connect failure and returns the result of the next attempt`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        val expected = SubmitPaymentResult(10L, "secret_123")
        every { delegate.submitPayment(orderId, amount) } throws
            ResourceAccessException("I/O error", ConnectException("Connection refused")) andThen expected

        val actual = client.submitPayment(orderId, amount)

        assertEquals(expected, actual)
        verify(exactly = 2) { delegate.submitPayment(orderId, amount) }
    }

    @Test
    fun `submitPayment - throws PaymentServiceUnavailableException once every attempt fails to connect`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        every { delegate.submitPayment(orderId, amount) } throws
            ResourceAccessException("I/O error", ConnectException("Connection refused"))

        assertThrows<PaymentServiceUnavailableException> { client.submitPayment(orderId, amount) }

        verify(exactly = MAX_ATTEMPTS) { delegate.submitPayment(orderId, amount) }
    }

    @Test
    fun `submitPayment - does not retry a read timeout and throws PaymentServiceUnavailableException`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        every { delegate.submitPayment(orderId, amount) } throws
            ResourceAccessException("I/O error", HttpTimeoutException("request timed out"))

        assertThrows<PaymentServiceUnavailableException> { client.submitPayment(orderId, amount) }

        verify(exactly = 1) { delegate.submitPayment(orderId, amount) }
    }

    @Test
    fun `submitPayment - does not retry a server error and rethrows it`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        every { delegate.submitPayment(orderId, amount) } throws
            HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)

        assertThrows<HttpServerErrorException> { client.submitPayment(orderId, amount) }

        verify(exactly = 1) { delegate.submitPayment(orderId, amount) }
    }

    @Test
    fun `submitPayment - does not retry a rejected payment and rethrows it`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        every { delegate.submitPayment(orderId, amount) } throws PaymentGatewayException("declined")

        assertThrows<PaymentGatewayException> { client.submitPayment(orderId, amount) }

        verify(exactly = 1) { delegate.submitPayment(orderId, amount) }
    }

    @Test
    fun `submitPayment - throws PaymentServiceUnavailableException without calling the delegate while the circuit breaker is open`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        circuitBreaker.transitionToOpenState()

        assertThrows<PaymentServiceUnavailableException> { client.submitPayment(orderId, amount) }

        verify(exactly = 0) { delegate.submitPayment(any(), any()) }
    }

    @Test
    fun `submitPayment - opens the circuit breaker once enough calls fail with a server error`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        val failedCalls = 10
        every { delegate.submitPayment(orderId, amount) } throws
            HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)

        repeat(failedCalls) { assertThrows<HttpServerErrorException> { client.submitPayment(orderId, amount) } }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.state)
        assertThrows<PaymentServiceUnavailableException> { client.submitPayment(orderId, amount) }
        verify(exactly = failedCalls) { delegate.submitPayment(orderId, amount) }
    }

    @Test
    fun `submitPayment - opens the circuit breaker once enough calls report the payment gateway unavailable`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        val failedCalls = 10
        every { delegate.submitPayment(orderId, amount) } throws PaymentGatewayUnavailableException("stripe down")

        repeat(failedCalls) {
            assertThrows<PaymentGatewayUnavailableException> { client.submitPayment(orderId, amount) }
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.state)
        verify(exactly = failedCalls) { delegate.submitPayment(orderId, amount) }
    }

    @Test
    fun `submitPayment - keeps the circuit breaker closed when payments are rejected`() {
        val orderId = 1L
        val amount = Money(BigDecimal("10.00"), Currency.USD)
        val rejectedCalls = 10
        every { delegate.submitPayment(orderId, amount) } throws PaymentGatewayException("declined")

        repeat(rejectedCalls) { assertThrows<PaymentGatewayException> { client.submitPayment(orderId, amount) } }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.state)
        verify(exactly = rejectedCalls) { delegate.submitPayment(orderId, amount) }
    }
}
