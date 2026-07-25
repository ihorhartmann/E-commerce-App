package com.hartmann.usecase.port.out.internal

import com.hartmann.usecase.port.out.internal.dto.ReservationItemDto

interface InventoryClient {
    fun reserve(orderId: Long, items: List<ReservationItemDto>)
}
