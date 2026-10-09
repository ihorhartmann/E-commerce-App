package com.hartmann.e2e.support.api.order

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class PlaceOrderRequest(
    val items: List<OrderItemRequest>,
    val address: AddressRequest,
)

data class OrderItemRequest(
    val productId: Long,
    val quantity: Int,
    val price: MoneyRequest,
)

data class MoneyRequest(
    val amount: BigDecimal,
    val currency: String,
)

data class AddressRequest(
    val city: String,
    val postalCode: String,
    val line1: String,
    val line2: String = "",
)

data class OrderResponse(
    val id: Long,
    val customerId: UUID,
    val paymentConfirmedAt: LocalDateTime?,
    val shipment: ShipmentInfoDto?,
    val cancellationReason: String?,
    val refund: RefundDto?,
    val items: List<OrderItemDto>,
    val totalPrice: MoneyDto,
    val address: AddressDto,
    val status: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val clientSecret: String?,
)

data class ShipmentInfoDto(
    val trackingNumber: String,
    val pickedUpAt: LocalDateTime?,
    val deliveredAt: LocalDateTime?,
    val returnedAt: LocalDateTime?,
)

data class RefundDto(
    val requestedAt: LocalDateTime,
    val amount: MoneyDto?,
    val confirmedAt: LocalDateTime?,
    val failedAt: LocalDateTime?,
)

data class OrderItemDto(val id: Long, val productId: Long, val quantity: Int, val price: MoneyDto)

data class MoneyDto(val amount: BigDecimal, val currency: String)

data class AddressDto(val city: String, val postalCode: String, val line1: String, val line2: String)

data class ListOrdersResponse(
    val items: List<OrderSummaryDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
)

data class OrderSummaryDto(
    val id: Long,
    val status: String,
    val totalPrice: MoneyDto,
    val itemCount: Int,
    val createdAt: LocalDateTime,
)
