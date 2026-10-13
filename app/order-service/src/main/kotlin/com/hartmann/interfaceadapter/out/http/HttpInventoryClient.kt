package com.hartmann.interfaceadapter.out.http

import com.hartmann.entities.value.ReservedItem
import com.hartmann.interfaceadapter.out.http.dto.CreateReservationResponse
import com.hartmann.interfaceadapter.out.http.dto.fromCreateReservationResponse
import com.hartmann.interfaceadapter.out.http.dto.toCreateReservationRequest
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

/**
 * Transport-level failures (a ResourceAccessException) are deliberately left to propagate as-is:
 * Resilience4jInventoryClient, which wraps this client, needs the underlying cause to tell a request
 * that never reached the inventory service (safe to retry) from one that may have.
 */
class HttpInventoryClient(private val restClient: RestClient) : InventoryClient {
    companion object {
        private val LOG = LoggerFactory.getLogger(HttpInventoryClient::class.java)
    }

    override fun reserve(orderId: Long, requestedItems: List<RequestedItem>): List<ReservedItem> {
        val request = toCreateReservationRequest(orderId, requestedItems)

        val response = try {
            restClient.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", "reserve-order-$orderId")
                .body(request)
                .retrieve()
                .toEntity<CreateReservationResponse>()
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
        val body = checkNotNull(response.body) { "Inventory service returned no reserved items for order $orderId" }
        return fromCreateReservationResponse(body)
    }
}
