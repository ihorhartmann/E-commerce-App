package com.hartmann.usecase.port.`in`.usecase

import com.hartmann.usecase.port.`in`.usecase.dto.CheckoutResult
import com.hartmann.entities.model.Order

interface PlaceOrderUseCase {
    fun execute(order: Order): CheckoutResult
}
