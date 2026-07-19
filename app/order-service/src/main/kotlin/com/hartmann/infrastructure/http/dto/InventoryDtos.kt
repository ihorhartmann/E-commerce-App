package com.hartmann.infrastructure.http.dto

data class ReservationItemRequest(val productId: Long, val quantity: Int)
data class CreateReservationRequest(val orderId: Long, val items: List<ReservationItemRequest>)
