package com.hartmann.interfaceadapter.out.http.inventory

import com.hartmann.entities.value.ReservedItem
import com.hartmann.interfaceadapter.out.http.common.httpCircuitBreakerConfig
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryFailureException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryUnavailableException
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem
import com.hartmann.usecase.port.out.internal.InventoryClient
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
import org.springframework.web.client.ResourceAccessException

/**
 * Decorates another InventoryClient with a retry and a circuit breaker. The retry wraps the
 * breaker, so every attempt is recorded by it, and an open breaker is never retried.
 */
class Resilience4jInventoryClient(
    private val delegate: InventoryClient,
    private val retry: Retry,
    private val circuitBreaker: CircuitBreaker,
) : InventoryClient {
    companion object {
        fun circuitBreakerConfig(): CircuitBreakerConfig = httpCircuitBreakerConfig(
            InventoryUnavailableException::class.java,
            InventoryFailureException::class.java,
        )
    }

    override fun reserve(orderId: Long, requestedItems: List<RequestedItem>): List<ReservedItem> =
        try {
            Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker) {
                delegate.reserve(orderId, requestedItems)
            }).get()
        } catch (e: CallNotPermittedException) {
            throw InventoryUnavailableException(orderId, e)
        } catch (e: ResourceAccessException) {
            throw InventoryUnavailableException(orderId, e)
        }
}
