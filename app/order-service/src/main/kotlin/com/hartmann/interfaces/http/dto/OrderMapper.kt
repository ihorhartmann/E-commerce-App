package com.hartmann.interfaces.http.dto

import com.hartmann.domain.exception.DomainValidationException
import com.hartmann.domain.model.Order
import com.hartmann.domain.model.OrderItem
import com.hartmann.domain.value.Address
import com.hartmann.domain.value.Currency
import com.hartmann.domain.value.Money
import org.springframework.stereotype.Component

@Component
class OrderMapper {
    fun toDomainModel(request: PlaceOrderRequestDto): Order = Order.createPending(
        userId = request.userId,
        items = request.items.map { item ->
            OrderItem(0, 0, item.productId, item.quantity.toUInt(), toDomainValue(item.price))
        },
        address = Address(request.address.country, request.address.city, request.address.postalCode, request.address.line1, request.address.line2),
    )

    fun fromDomainModel(order: Order): OrderResponse = OrderResponse(
        id = order.id,
        userId = order.userId,
        reservationId = order.reservationId,
        paymentId = order.paymentId,
        shipmentId = order.shipmentId,
        items = order.items.map { OrderItemResponseDto(it.id, it.productId, it.quantity, MoneyResponseDto(it.price.amount, it.price.currency.name)) },
        totalPrice = MoneyResponseDto(order.totalPrice().amount, order.totalPrice().currency.name),
        address = AddressResponseDto(order.address.country, order.address.city, order.address.postalCode, order.address.line1, order.address.line2),
        status = order.status.name,
        statusDescription = order.statusDescription,
        createdAt = order.createdAt,
        updatedAt = order.updatedAt,
    )

    private fun toDomainValue(request: MoneyRequestDto): Money = Money(
        amount = request.amount,
        currency = try {
            Currency.valueOf(request.currency.uppercase())
        } catch (_: IllegalArgumentException) {
            throw DomainValidationException("unsupported currency: ${request.currency}")
        },
    )
}
