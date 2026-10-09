package com.hartmann.usecase.port.out.repository

import com.hartmann.entities.model.Order
import com.hartmann.usecase.port.out.repository.dto.OrderPage
import java.util.UUID

interface OrderRepository {
    fun save(order: Order): Order
    fun getOrder(id: Long): Order
    fun findCustomerOrders(customerId: UUID, page: Int, size: Int): OrderPage
}
