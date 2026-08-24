package com.hartmann.interfaceadapter.`in`.http.dto

import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class PlaceOrderRequest(
    @field:Positive val userId: Long,
    @field:NotEmpty @field:Valid val items: List<OrderItemRequest>,
    @field:NotNull @field:Valid val address: AddressRequest,
)

fun fromPlaceOrderRequest(request: PlaceOrderRequest): Order = Order.createNew(
    userId = request.userId,
    items = request.items.map { fromOrderItemRequest(it) },
    address = fromAddressRequest(request.address),
)

data class OrderItemRequest(
    @field:Positive val productId: Long,
    @field:Positive val quantity: Int,
    @field:NotNull @field:Valid val price: MoneyRequest,
)

private fun fromOrderItemRequest(request: OrderItemRequest): OrderItem =
    OrderItem.createNew(request.productId, request.quantity, fromMoneyRequest(request.price))

data class MoneyRequest(
    @field:NotNull @field:Positive val amount: BigDecimal,
    @field:NotNull val currency: String,
)

private fun fromMoneyRequest(request: MoneyRequest): Money = Money(
    amount = request.amount,
    currency = Currency.fromCode(request.currency),
)

data class AddressRequest(
    @field:NotBlank val city: String,
    @field:NotBlank val postalCode: String,
    @field:NotBlank val line1: String,
    val line2: String = "",
)

private fun fromAddressRequest(request: AddressRequest): Address =
    Address(request.city, request.postalCode, request.line1, request.line2)
