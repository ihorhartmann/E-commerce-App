package com.hartmann.usecase.port.`in`.usecase

import com.hartmann.entities.model.Order
import java.util.UUID

interface CancelOrderUseCase {
    fun execute(orderId: Long, customerId: UUID): Order
}
