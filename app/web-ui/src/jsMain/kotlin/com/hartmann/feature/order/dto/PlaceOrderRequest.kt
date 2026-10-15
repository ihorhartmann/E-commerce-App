package com.hartmann.feature.order.dto

import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.OrderLine
import kotlinx.serialization.Serializable

@Serializable
data class PlaceOrderRequest(val items: List<OrderItemRequest>, val address: AddressRequest)

@Serializable
data class OrderItemRequest(val productId: Long, val quantity: Int)

@Serializable
data class AddressRequest(val city: String, val postalCode: String, val line1: String, val line2: String)

fun toPlaceOrderRequest(lines: List<OrderLine>, address: Address): PlaceOrderRequest = PlaceOrderRequest(
    items = lines.map { OrderItemRequest(it.productId, it.quantity) },
    address = AddressRequest(address.city, address.postalCode, address.line1, address.line2),
)
