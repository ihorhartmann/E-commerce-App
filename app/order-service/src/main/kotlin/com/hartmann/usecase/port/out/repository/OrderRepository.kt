package com.hartmann.usecase.port.out.repository

import com.hartmann.entities.model.Order

interface OrderRepository {
    fun save(order: Order): Order
    fun getOrder(id: Long): Order
}
