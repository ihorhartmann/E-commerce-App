package com.hartmann.usecase.port.out.http

import com.hartmann.usecase.port.out.http.dto.ReservationItemDto

interface InventoryClient {
    fun reserve(orderId: Long, items: List<ReservationItemDto>)
}
