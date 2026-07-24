package com.hartmann.interfaceadapter.`in`.http.dto

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import org.springframework.stereotype.Component

@Component
class OrderMapper {
    fun toDomainModel(request: PlaceOrderRequestDto): Order = Order.create(
        userId = request.userId,
        items = request.items.map { item ->
            OrderItem(0, 0, item.productId, item.quantity.toUInt(), toDomainValue(item.price))
        },
        address = Address(request.address.city, request.address.postalCode, request.address.line1, request.address.line2),
    )

    fun fromDomainModel(order: Order, clientSecret: String? = null): OrderResponse = OrderResponse(
        id = order.id,
        userId = order.userId,
        paymentConfirmedAt = order.paymentConfirmedAt,
        shipment = order.shipment?.let {
            ShipmentResponseDto(it.trackingNumber, it.pickedUpAt, it.deliveredAt, it.returnedAt)
        },
        cancellationReason = order.cancellationReason,
        refund = order.refund?.let {
            RefundResponseDto(
                requestedAt = it.requestedAt,
                amount = it.amount?.let { amount -> MoneyResponseDto(amount.amount, amount.currency.name) },
                confirmedAt = it.confirmedAt,
                failedAt = it.failedAt,
            )
        },
        items = order.items.map { OrderItemResponseDto(it.id, it.productId, it.quantity, MoneyResponseDto(it.price.amount, it.price.currency.name)) },
        totalPrice = MoneyResponseDto(order.totalPrice().amount, order.totalPrice().currency.name),
        address = AddressResponseDto(order.address.city, order.address.postalCode, order.address.line1, order.address.line2),
        status = order.status.name,
        createdAt = order.createdAt,
        updatedAt = order.updatedAt,
        clientSecret = clientSecret,
    )

    private fun toDomainValue(request: MoneyRequestDto): Money = Money(
        amount = request.amount,
        currency = Currency.fromCode(request.currency),
    )
}
