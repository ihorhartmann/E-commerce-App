package com.hartmann.application.port.out.repository

import com.hartmann.domain.model.Order

interface OrderRepository {
    fun save(order: Order): Order
    fun getOrder(id: Long): Order
}
