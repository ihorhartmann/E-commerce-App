package com.hartmann.feature.order

import com.hartmann.core.model.Page
import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.Order
import com.hartmann.feature.order.model.OrderLine
import com.hartmann.feature.order.model.OrderSummary
import com.hartmann.feature.order.model.PlacedOrder

/** The signed-in customer's orders - the order-service only ever shows a customer their own. */
interface OrderApi {
    suspend fun placeOrder(lines: List<OrderLine>, address: Address): PlacedOrder

    suspend fun listOrders(page: Int, size: Int): Page<OrderSummary>

    suspend fun getOrder(id: Long): Order

    suspend fun cancelOrder(id: Long): Order
}
