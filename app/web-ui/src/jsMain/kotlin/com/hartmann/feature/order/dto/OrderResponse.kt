package com.hartmann.feature.order.dto

import com.hartmann.core.network.dto.MoneyDto
import com.hartmann.core.network.dto.fromMoneyDto
import com.hartmann.feature.order.model.Address
import com.hartmann.feature.order.model.Order
import com.hartmann.feature.order.model.OrderItem
import com.hartmann.feature.order.model.OrderStatus
import com.hartmann.feature.order.model.PlacedOrder
import com.hartmann.feature.order.model.RefundProgress
import com.hartmann.feature.order.model.ShipmentProgress
import kotlinx.serialization.Serializable

@Serializable
data class OrderResponse(
    val id: Long,
    val status: OrderStatus,
    val cancellationReason: String?,
    val items: List<OrderItemResponseDto>,
    val totalPrice: MoneyDto,
    val address: AddressResponseDto,
    val createdAt: String,
    val paymentConfirmedAt: String?,
    val shipment: ShipmentResponseDto?,
    val refund: RefundResponseDto?,
    val clientSecret: String?,
)

@Serializable
data class OrderItemResponseDto(val productName: String, val quantity: Int, val price: MoneyDto)

@Serializable
data class AddressResponseDto(val city: String, val postalCode: String, val line1: String, val line2: String)

@Serializable
data class ShipmentResponseDto(val pickedUpAt: String?, val deliveredAt: String?, val returnedAt: String?)

@Serializable
data class RefundResponseDto(val requestedAt: String, val amount: MoneyDto?, val confirmedAt: String?, val failedAt: String?)

fun fromOrderResponse(response: OrderResponse): Order = Order(
    id = response.id,
    status = response.status,
    cancellationReason = response.cancellationReason,
    items = response.items.map { OrderItem(it.productName, it.quantity, fromMoneyDto(it.price)) },
    total = fromMoneyDto(response.totalPrice),
    address = Address(response.address.city, response.address.postalCode, response.address.line1, response.address.line2),
    createdAt = response.createdAt,
    paymentConfirmedAt = response.paymentConfirmedAt,
    shipment = response.shipment?.let { ShipmentProgress(it.pickedUpAt, it.deliveredAt, it.returnedAt) },
    refund = response.refund?.let { fromRefundResponseDto(it) },
)

fun toPlacedOrder(response: OrderResponse): PlacedOrder =
    PlacedOrder(response.id, fromMoneyDto(response.totalPrice), response.clientSecret)

private fun fromRefundResponseDto(response: RefundResponseDto): RefundProgress = RefundProgress(
    requestedAt = response.requestedAt,
    amount = response.amount?.let { fromMoneyDto(it) },
    confirmedAt = response.confirmedAt,
    failedAt = response.failedAt,
)
