package com.hartmann.interfaceadapter.out.http.dto

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RequestedItem
import java.math.BigDecimal

data class ReservationItemRequest(val productId: Long, val quantity: Int)

data class CreateReservationRequest(val orderId: Long, val items: List<ReservationItemRequest>)

fun toCreateReservationRequest(orderId: Long, requestedItems: List<RequestedItem>): CreateReservationRequest =
    CreateReservationRequest(orderId, requestedItems.map { ReservationItemRequest(it.productId, it.quantity) })

data class MoneyDto(val amount: BigDecimal, val currency: String)

private fun fromMoneyDto(money: MoneyDto): Money = Money(money.amount, Currency.fromCode(money.currency))

data class ReservedItemResponse(val productId: Long, val price: MoneyDto)

data class CreateReservationResponse(val items: List<ReservedItemResponse>)

fun fromCreateReservationResponse(response: CreateReservationResponse): Map<Long, Money> =
    response.items.associate { it.productId to fromMoneyDto(it.price) }
