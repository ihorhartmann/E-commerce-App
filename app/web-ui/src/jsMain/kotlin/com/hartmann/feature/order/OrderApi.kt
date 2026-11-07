package com.hartmann.feature.order

import com.hartmann.core.model.Page
import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.Order
import com.hartmann.feature.order.model.OrderLine
import com.hartmann.feature.order.model.OrderSummary
import com.hartmann.feature.order.model.PlacedOrder

/** The signed-in customer's orders - the order-service only ever shows a customer their own. */
interface OrderApi {
    /**
     * Places the order. Sent again with the same [idempotencyKey], lines and address, it is answered with the order
     * already placed for that key instead of placing another one.
     */
    suspend fun placeOrder(lines: List<OrderLine>, address: Address, idempotencyKey: String): PlacedOrder

    suspend fun listOrders(page: Int, size: Int): Page<OrderSummary>

    suspend fun getOrder(id: Long): Order

    suspend fun cancelOrder(id: Long): Order
}
