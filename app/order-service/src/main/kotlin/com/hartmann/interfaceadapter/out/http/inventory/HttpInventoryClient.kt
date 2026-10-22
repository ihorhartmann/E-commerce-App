package com.hartmann.interfaceadapter.out.http.inventory

import com.hartmann.entities.value.ReservedItem
import com.hartmann.interfaceadapter.out.http.common.dto.toProblemResponse
import com.hartmann.interfaceadapter.out.http.inventory.dto.CreateReservationResponse
import com.hartmann.interfaceadapter.out.http.inventory.dto.fromCreateReservationResponse
import com.hartmann.interfaceadapter.out.http.inventory.dto.toCreateReservationRequest
import com.hartmann.interfaceadapter.out.http.inventory.exception.InsufficientStockException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InvalidInventoryResponseException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryFailureException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryRequestRejectedException
import com.hartmann.interfaceadapter.out.http.inventory.exception.InventoryUnavailableException
import com.hartmann.usecase.port.`in`.usecase.dto.RequestedItem
import com.hartmann.usecase.port.out.internal.InventoryClient
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

/** Inventory's code for a reservation rejected for lack of stock. */
private const val INSUFFICIENT_STOCK_CODE = "INVENTORY.INSUFFICIENT_STOCK"

/**
 * Transport-level failures (a ResourceAccessException) are deliberately left to propagate as-is:
 * Resilience4jInventoryClient, which wraps this client, needs the underlying cause to tell a request
 * that never reached the inventory service (safe to retry) from one that may have.
 */
class HttpInventoryClient(private val restClient: RestClient) : InventoryClient {
    override fun reserve(orderId: Long, requestedItems: List<RequestedItem>): List<ReservedItem> {
        val request = toCreateReservationRequest(orderId, requestedItems)

        val response = try {
            restClient.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", "reserve-order-$orderId")
                .body(request)
                .retrieve()
                .toEntity<CreateReservationResponse>()
        } catch (e: HttpStatusCodeException) {
            throw fromErrorResponse(orderId, e)
        }

        check(response.statusCode == HttpStatus.CREATED) {
            "Inventory service returned unexpected status ${response.statusCode} for order $orderId"
        }
        val body = checkNotNull(response.body) { "Inventory service returned no reserved items for order $orderId" }
        return try {
            fromCreateReservationResponse(body)
        } catch (e: RuntimeException) {
            throw InvalidInventoryResponseException(orderId, e)
        }
    }

    /**
     * Maps an error response by inventory's code, never by the status alone: a 409 is insufficient stock as well as
     * a request still in progress. Whatever inventory asks to retry is reported as unavailable, so the caller is
     * asked to retry too; any other failure is this service's own, not the caller's.
     */
    private fun fromErrorResponse(orderId: Long, e: HttpStatusCodeException): RuntimeException {
        val problem = toProblemResponse(e)
        return when {
            problem?.code == INSUFFICIENT_STOCK_CODE -> InsufficientStockException(orderId, e)
            problem?.retryable == true -> InventoryUnavailableException(orderId, e)
            e.statusCode.is5xxServerError -> InventoryFailureException(orderId, e.statusCode.value(), problem?.code, e)
            else -> InventoryRequestRejectedException(orderId, e.statusCode.value(), problem?.code, e)
        }
    }
}
