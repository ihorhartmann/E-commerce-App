package com.hartmann.interfaceadapter.`in`.http.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.LocalDateTime

data class PlaceOrderRequestDto(
    @field:Positive val userId: Long,
    @field:NotEmpty @field:Valid val items: List<OrderItemRequestDto>,
    @field:NotNull @field:Valid val address: AddressRequestDto,
)

data class OrderItemRequestDto(
    @field:Positive val productId: Long,
    @field:Positive val quantity: Int,
    @field:NotNull @field:Valid val price: MoneyRequestDto,
)

data class MoneyRequestDto(
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:NotNull val currency: String,
)

data class AddressRequestDto(
    @field:NotBlank val city: String,
    @field:NotBlank val postalCode: String,
    @field:NotBlank val line1: String,
    val line2: String = "",
)

data class OrderResponse(
    val id: Long,
    val userId: Long,
    val paymentConfirmedAt: LocalDateTime?,
    val shipment: ShipmentResponseDto?,
    val cancellationReason: String?,
    val refund: RefundResponseDto?,
    val items: List<OrderItemResponseDto>,
    val totalPrice: MoneyResponseDto,
    val address: AddressResponseDto,
    val status: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val clientSecret: String? = null,
)

data class ShipmentResponseDto(
    val trackingNumber: String,
    val pickedUpAt: LocalDateTime?,
    val deliveredAt: LocalDateTime?,
    val returnedAt: LocalDateTime?,
)

data class RefundResponseDto(
    val requestedAt: LocalDateTime,
    val amount: MoneyResponseDto?,
    val confirmedAt: LocalDateTime?,
    val failedAt: LocalDateTime?,
)

data class OrderItemResponseDto(val id: Long, val productId: Long, val quantity: UInt, val price: MoneyResponseDto)
data class MoneyResponseDto(val amount: BigDecimal, val currency: String)
data class AddressResponseDto(val city: String, val postalCode: String, val line1: String, val line2: String)
