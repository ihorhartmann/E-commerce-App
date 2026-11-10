package com.hartmann.feature.order

import com.hartmann.core.network.IDEMPOTENCY_KEY_HEADER
import com.hartmann.core.network.bodyOrThrow
import com.hartmann.core.network.exception.ApiException
import com.hartmann.core.model.Page
import com.hartmann.feature.order.exception.OrderAlreadyPaidException
import com.hartmann.feature.order.dto.ListOrdersResponse
import com.hartmann.feature.order.dto.OrderResponse
import com.hartmann.feature.order.dto.fromListOrdersResponse
import com.hartmann.feature.order.dto.fromOrderResponse
import com.hartmann.feature.order.dto.toPlaceOrderRequest
import com.hartmann.feature.order.dto.toPlacedOrder
import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.Order
import com.hartmann.feature.order.model.OrderLine
import com.hartmann.feature.order.model.OrderSummary
import com.hartmann.feature.order.model.PlacedOrder
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class KtorOrderApi private constructor(private val client: HttpClient) : OrderApi {
    override suspend fun placeOrder(lines: List<OrderLine>, address: Address, idempotencyKey: String): PlacedOrder =
        toPlacedOrder(
            client.post(ORDERS_PATH) {
                header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(toPlaceOrderRequest(lines, address))
            }.bodyOrThrow<OrderResponse>()
        )

    override suspend fun listOrders(page: Int, size: Int): Page<OrderSummary> =
        fromListOrdersResponse(
            client.get(ORDERS_PATH) {
                parameter("page", page)
                parameter("size", size)
            }.bodyOrThrow<ListOrdersResponse>()
        )

    override suspend fun getOrder(id: Long): Order =
        fromOrderResponse(client.get("$ORDERS_PATH/$id").bodyOrThrow<OrderResponse>())

    override suspend fun cancelOrder(id: Long): Order =
        try {
            fromOrderResponse(client.post("$ORDERS_PATH/$id/cancel").bodyOrThrow<OrderResponse>())
        } catch (e: ApiException) {
            if (e.code == OrderAlreadyPaidException.CODE) throw OrderAlreadyPaidException(id) else throw e
        }

    companion object {
        private const val ORDERS_PATH = "api/v1/orders"

        fun create(client: HttpClient): OrderApi = KtorOrderApi(client)
    }
}
