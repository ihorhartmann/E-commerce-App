package com.hartmann.interfaceadapter.out.http

import com.hartmann.entities.model.OrderItem
import com.hartmann.usecase.port.out.internal.InventoryClient
import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import com.hartmann.interfaceadapter.out.http.dto.toCreateReservationRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

@Component
class HttpInventoryClient(
    @Value($$"${inventory-service.url}") baseUrl: String,
    loggingInterceptor: ClientHttpRequestInterceptor,
) : InventoryClient {
    companion object {
        private val LOG = LoggerFactory.getLogger(HttpInventoryClient::class.java)
    }

    private val restClient = RestClient.builder().baseUrl(baseUrl).requestInterceptor(loggingInterceptor).build()

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
