package com.hartmann.application.port.out.http

import com.hartmann.application.port.out.http.dto.ReservationItemDto

interface InventoryClient {
    fun reserve(orderId: Long, items: List<ReservationItemDto>)
}
