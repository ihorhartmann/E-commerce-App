package com.hartmann.usecase.port.`in`.usecase

import com.hartmann.entities.model.Order
import java.util.UUID

interface GetOrderUseCase {
    /** Returns the order only to the customer who placed it; to anyone else it doesn't exist. */
    fun execute(orderId: Long, customerId: UUID): Order
}
