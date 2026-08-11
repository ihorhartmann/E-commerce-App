package com.hartmann.interfaceadapter.`in`.http.dto

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RefundInfo
import com.hartmann.entities.value.ShipmentInfo
import java.math.BigDecimal
import java.time.LocalDateTime

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

fun toOrderResponse(order: Order, clientSecret: String? = null): OrderResponse = OrderResponse(
    id = order.id,
    userId = order.userId,
    paymentConfirmedAt = order.paymentConfirmedAt,
    shipment = order.shipment?.let { toShipmentResponseDto(it) },
    cancellationReason = order.cancellationReason?.name,
    refund = order.refund?.let { toRefundResponseDto(it) },
    items = order.items.map { toOrderItemResponseDto(it) },
    totalPrice = toMoneyResponseDto(order.totalPrice()),
    address = toAddressResponseDto(order.address),
    status = order.status.name,
    createdAt = order.createdAt,
    updatedAt = order.updatedAt,
    clientSecret = clientSecret,
)

data class ShipmentResponseDto(
    val trackingNumber: String,
    val pickedUpAt: LocalDateTime?,
    val deliveredAt: LocalDateTime?,
    val returnedAt: LocalDateTime?,
)

private fun toShipmentResponseDto(shipment: ShipmentInfo): ShipmentResponseDto = ShipmentResponseDto(
    trackingNumber = shipment.trackingNumber,
    pickedUpAt = shipment.pickedUpAt,
    deliveredAt = shipment.deliveredAt,
    returnedAt = shipment.returnedAt,
)

data class RefundResponseDto(
    val requestedAt: LocalDateTime,
    val amount: MoneyResponseDto?,
    val confirmedAt: LocalDateTime?,
    val failedAt: LocalDateTime?,
)

private fun toRefundResponseDto(refund: RefundInfo): RefundResponseDto = RefundResponseDto(
    requestedAt = refund.requestedAt,
    amount = refund.amount?.let { toMoneyResponseDto(it) },
    confirmedAt = refund.confirmedAt,
    failedAt = refund.failedAt,
)

data class OrderItemResponseDto(val id: Long, val productId: Long, val quantity: Int, val price: MoneyResponseDto)

private fun toOrderItemResponseDto(item: OrderItem): OrderItemResponseDto = OrderItemResponseDto(
    id = item.id,
    productId = item.productId,
    quantity = item.quantity,
    price = toMoneyResponseDto(item.price),
)

data class MoneyResponseDto(val amount: BigDecimal, val currency: String)

private fun toMoneyResponseDto(money: Money): MoneyResponseDto =
    MoneyResponseDto(money.amount, money.currency.name)

data class AddressResponseDto(val city: String, val postalCode: String, val line1: String, val line2: String)

private fun toAddressResponseDto(address: Address): AddressResponseDto = AddressResponseDto(
    city = address.city,
    postalCode = address.postalCode,
    line1 = address.line1,
    line2 = address.line2,
)
