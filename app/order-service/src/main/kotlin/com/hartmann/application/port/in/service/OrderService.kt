package com.hartmann.application.port.`in`.service

import com.hartmann.domain.model.Order

interface OrderService {
    fun placeOrder(order: Order): Order
    fun getOrder(orderId: Long): Order
}
