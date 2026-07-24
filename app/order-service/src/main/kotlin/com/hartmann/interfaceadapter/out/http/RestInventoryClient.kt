package com.hartmann.interfaceadapter.out.http

import com.hartmann.usecase.port.out.http.InventoryClient
import com.hartmann.usecase.port.out.http.dto.ReservationItemDto
import com.hartmann.usecase.port.out.http.exception.InsufficientStockException
import com.hartmann.interfaceadapter.out.http.dto.CreateReservationRequest
import com.hartmann.interfaceadapter.out.http.dto.ReservationItemRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

@Component
class RestInventoryClient(
    @Value($$"${inventory-service.url}") baseUrl: String,
    loggingInterceptor: LoggingClientHttpRequestInterceptor,
) : InventoryClient {
    companion object {
        private val LOG = LoggerFactory.getLogger(RestInventoryClient::class.java)
    }

    private val restClient = RestClient.builder().baseUrl(baseUrl).requestInterceptor(loggingInterceptor).build()

    override fun reserve(orderId: Long, items: List<ReservationItemDto>) {
        val request = CreateReservationRequest(orderId, items.map { ReservationItemRequest(it.productId, it.quantity) })

        val response = try {
            restClient.post()
                .uri("/api/v1/reservations")
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
