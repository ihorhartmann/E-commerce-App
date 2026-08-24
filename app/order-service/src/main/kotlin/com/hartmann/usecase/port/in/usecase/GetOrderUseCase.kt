package com.hartmann.usecase.port.`in`.usecase

import com.hartmann.entities.model.Order

interface GetOrderUseCase {
    fun execute(orderId: Long): Order
}
