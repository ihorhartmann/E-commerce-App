package com.hartmann.interfaceadapter.out.http

import com.hartmann.entities.model.OrderItem
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import com.hartmann.interfaceadapter.out.http.dto.toCreateReservationRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * Transport-level failures (a ResourceAccessException) are deliberately left to propagate as-is:
 * Resilience4jInventoryClient, which wraps this client, needs the underlying cause to tell a request
 * that never reached the inventory service (safe to retry) from one that may have.
 */
class HttpInventoryClient(private val restClient: RestClient) : InventoryClient {
    companion object {
        private val LOG = LoggerFactory.getLogger(HttpInventoryClient::class.java)
    }

    override fun reserve(orderId: Long, items: List<OrderItem>) {
        val request = toCreateReservationRequest(orderId, items)

        val response = try {
            restClient.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", "reserve-order-$orderId")
                .body(request)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpClientErrorException) {
            if (e.statusCode == HttpStatus.CONFLICT) {
                throw InsufficientStockException("Insufficient stock for order $orderId")
            }
            LOG.error(e.message, e)
            throw e
        }

        check(response.statusCode == HttpStatus.CREATED) {
            "Inventory service returned unexpected status ${response.statusCode} for order $orderId"
        }
    }
}
