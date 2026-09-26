package com.hartmann.interfaceadapter.out.http

import com.hartmann.entities.value.Money
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
import com.hartmann.usecase.port.out.internal.exception.PaymentGatewayUnavailableException
import com.hartmann.usecase.port.out.internal.exception.PaymentServiceUnavailableException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
import org.springframework.web.client.ResourceAccessException

/**
 * Decorates another PaymentClient with a retry and a circuit breaker. The retry wraps the breaker,
 * so every attempt is recorded by it, and an open breaker is never retried.
 */
class Resilience4jPaymentClient(
    private val delegate: PaymentClient,
    private val retry: Retry,
    private val circuitBreaker: CircuitBreaker,
) : PaymentClient {
    companion object {
        fun circuitBreakerConfig(): CircuitBreakerConfig =
            httpCircuitBreakerConfig(PaymentGatewayUnavailableException::class.java)
    }

    override fun submitPayment(orderId: Long, amount: Money): SubmitPaymentResult =
        try {
            Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker) {
                delegate.submitPayment(orderId, amount)
            }).get()
        } catch (e: CallNotPermittedException) {
            throw PaymentServiceUnavailableException("Payment service circuit breaker is open, rejected order $orderId", e)
        } catch (e: ResourceAccessException) {
            throw PaymentServiceUnavailableException("Payment service unreachable for order $orderId", e)
        }
}
