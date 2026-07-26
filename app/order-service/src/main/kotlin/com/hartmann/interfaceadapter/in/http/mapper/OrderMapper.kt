package com.hartmann.interfaceadapter.`in`.http.mapper

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RefundInfo
import com.hartmann.entities.value.ShipmentInfo
import com.hartmann.interfaceadapter.`in`.http.dto.AddressRequest
import com.hartmann.interfaceadapter.`in`.http.dto.AddressResponseDto
import com.hartmann.interfaceadapter.`in`.http.dto.MoneyRequest
import com.hartmann.interfaceadapter.`in`.http.dto.MoneyResponseDto
import com.hartmann.interfaceadapter.`in`.http.dto.OrderItemRequest
import com.hartmann.interfaceadapter.`in`.http.dto.OrderItemResponseDto
import com.hartmann.interfaceadapter.`in`.http.dto.OrderResponse
import com.hartmann.interfaceadapter.`in`.http.dto.PlaceOrderRequest
import com.hartmann.interfaceadapter.`in`.http.dto.RefundResponseDto
import com.hartmann.interfaceadapter.`in`.http.dto.ShipmentResponseDto
import org.springframework.stereotype.Component

@Component
class OrderMapper {
    fun fromPlaceOrderRequest(request: PlaceOrderRequest): Order = Order.create(
        userId = request.userId,
        items = request.items.map { fromOrderItemRequest(it) },
        address = fromAddressRequest(request.address),
    )

    fun toOrderResponse(order: Order, clientSecret: String? = null): OrderResponse = OrderResponse(
        id = order.id,
        userId = order.userId,
        paymentConfirmedAt = order.paymentConfirmedAt,
        shipment = order.shipment?.let { toShipmentResponseDto(it) },
        cancellationReason = order.cancellationReason,
        refund = order.refund?.let { toRefundResponseDto(it) },
        items = order.items.map { toOrderItemResponseDto(it) },
        totalPrice = toMoneyResponseDto(order.totalPrice()),
        address = toAddressResponseDto(order.address),
        status = order.status.name,
        createdAt = order.createdAt,
        updatedAt = order.updatedAt,
        clientSecret = clientSecret,
    )

    private fun fromOrderItemRequest(request: OrderItemRequest): OrderItem =
        OrderItem(0, 0, request.productId, request.quantity, fromMoneyRequest(request.price))

    private fun fromMoneyRequest(request: MoneyRequest): Money = Money(
        amount = request.amount,
        currency = Currency.fromCode(request.currency),
    )

    private fun fromAddressRequest(request: AddressRequest): Address =
        Address(request.city, request.postalCode, request.line1, request.line2)

    private fun toShipmentResponseDto(shipment: ShipmentInfo): ShipmentResponseDto = ShipmentResponseDto(
        trackingNumber = shipment.trackingNumber,
        pickedUpAt = shipment.pickedUpAt,
        deliveredAt = shipment.deliveredAt,
        returnedAt = shipment.returnedAt,
    )

    private fun toRefundResponseDto(refund: RefundInfo): RefundResponseDto = RefundResponseDto(
        requestedAt = refund.requestedAt,
        amount = refund.amount?.let { toMoneyResponseDto(it) },
        confirmedAt = refund.confirmedAt,
        failedAt = refund.failedAt,
    )

    private fun toOrderItemResponseDto(item: OrderItem): OrderItemResponseDto = OrderItemResponseDto(
        id = item.id,
        productId = item.productId,
        quantity = item.quantity,
        price = toMoneyResponseDto(item.price),
    )

    private fun toMoneyResponseDto(money: Money): MoneyResponseDto =
        MoneyResponseDto(money.amount, money.currency.name)

    private fun toAddressResponseDto(address: Address): AddressResponseDto = AddressResponseDto(
        city = address.city,
        postalCode = address.postalCode,
        line1 = address.line1,
        line2 = address.line2,
    )
}
