package com.hartmann.usecase.port.out.internal

import com.hartmann.entities.model.OrderItem

interface InventoryClient {
    fun reserve(orderId: Long, items: List<OrderItem>)
}
