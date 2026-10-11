package com.hartmann.usecase.port.out.repository

import com.hartmann.entities.model.Order
import com.hartmann.usecase.port.out.repository.dto.OrderPage
import java.util.UUID

interface OrderRepository {
    /** The id of the next order, handed out before the order is stored: its stock is reserved for this id first. */
    fun nextId(): Long

    /** Stores a new order under the id it was given by [nextId]. */
    fun create(order: Order): Order
    fun save(order: Order): Order
    fun getOrder(id: Long): Order
    fun findCustomerOrders(customerId: UUID, page: Int, size: Int): OrderPage
}
