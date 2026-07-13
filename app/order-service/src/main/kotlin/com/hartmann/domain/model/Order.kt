package com.hartmann.domain.model

import com.hartmann.domain.value.Address
import com.hartmann.domain.value.Currency
import com.hartmann.domain.value.Money
import java.time.LocalDateTime
import java.math.BigDecimal

data class Order(
    val id: Long,
    val userId: Long,
    val reservationId: Long? = null,
    val paymentId: Long? = null,
    val shipmentId: Long? = null,
    val items: List<OrderItem>,
    val address: Address,
    val status: OrderStatus,
    val statusDescription: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime? = null,
) {
    init {
        require(userId > 0) { "userId must be positive" }
        require(items.isNotEmpty()) { "order must contain at least one item" }
        require(items.map { it.price.currency }.distinct().size == 1) { "all order item currencies must match" }
    }

    fun totalPrice(): Money {
        val currency: Currency = items.first().price.currency
        val amount = items.fold(BigDecimal.ZERO) { total, item ->
            total + item.price.amount.multiply(item.quantity.toString().toBigDecimal())
        }

        return Money(amount, currency)
    }

    companion object {
        fun createPending(
            userId: Long,
            items: List<OrderItem>,
            address: Address,
        ): Order {
            return Order(
                id = 0,
                userId = userId,
                items = items,
                address = address,
                status = OrderStatus.PENDING,
                statusDescription = "Order placed",
                createdAt = LocalDateTime.now(),
            )
        }
    }
}

enum class OrderStatus {
    PENDING, STOCK_RESERVED, PAID, SHIPMENT_CREATED, DELIVERED, CANCELLED
}
