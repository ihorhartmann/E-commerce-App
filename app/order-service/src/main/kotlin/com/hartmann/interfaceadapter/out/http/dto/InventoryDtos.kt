package com.hartmann.interfaceadapter.out.http.dto

import com.hartmann.entities.model.OrderItem

data class ReservationItemRequest(val productId: Long, val quantity: Int)

private fun toReservationItemRequest(item: OrderItem): ReservationItemRequest =
    ReservationItemRequest(item.productId, item.quantity)

data class CreateReservationRequest(val orderId: Long, val items: List<ReservationItemRequest>)

fun toCreateReservationRequest(orderId: Long, items: List<OrderItem>): CreateReservationRequest =
    CreateReservationRequest(orderId, items.map { toReservationItemRequest(it) })
