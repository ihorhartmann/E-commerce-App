package com.hartmann.application.port.`in`.service

import com.hartmann.application.port.`in`.service.dto.CheckoutResult
import com.hartmann.domain.model.Order

interface OrderService {
    fun placeOrder(order: Order): CheckoutResult
    fun getOrder(orderId: Long): Order
}
