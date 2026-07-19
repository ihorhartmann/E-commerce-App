package com.hartmann.domain.model

import com.hartmann.domain.exception.DomainException
import com.hartmann.domain.value.Address
import com.hartmann.domain.value.Currency
import com.hartmann.domain.value.Money
import java.time.LocalDateTime
import java.math.BigDecimal

data class Order(
    val id: Long,
    val userId: Long,
    val paymentId: Long? = null,
    val shipmentId: Long? = null,
    val trackingNumber: String? = null,
    val cancellationReason: String? = null,
    val refunded: Money? = null,
    val items: List<OrderItem>,
    val address: Address,
    val status: OrderStatus,
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

    /** Reservation request failed for a reason other than insufficient stock (e.g. Inventory unreachable). */
    fun failReservation(): Order {
        requireStatus("fail reservation", OrderStatus.CREATED)
        return copy(status = OrderStatus.CANCELLED, cancellationReason = "Reservation failed")
    }

    /** Reservation request failed specifically because the requested quantity is not available. */
    fun outOfStock(): Order {
        requireStatus("mark out of stock", OrderStatus.CREATED)
        return copy(status = OrderStatus.CANCELLED, cancellationReason = "Out of stock")
    }

    fun reserveStock(): Order {
        requireStatus("reserve stock", OrderStatus.CREATED)
        return copy(status = OrderStatus.STOCK_RESERVED)
    }

    fun requestPayment(paymentId: Long): Order {
        requireStatus("request payment", OrderStatus.STOCK_RESERVED)
        return copy(status = OrderStatus.PAYMENT_SUBMITTED, paymentId = paymentId)
    }

    fun confirmPayment(paymentId: Long): Order {
        requireStatus("confirm payment", OrderStatus.PAYMENT_SUBMITTED)
        return copy(status = OrderStatus.PAYMENT_CONFIRMED, paymentId = paymentId)
    }

    fun failPayment(): Order {
        requireStatus("fail payment", OrderStatus.STOCK_RESERVED, OrderStatus.PAYMENT_SUBMITTED)
        return copy(status = OrderStatus.CANCELLED, cancellationReason = "Payment failed")
    }

    fun failShipmentCreation(): Order {
        requireStatus("fail shipment creation", OrderStatus.PAYMENT_CONFIRMED)
        return copy(status = OrderStatus.REFUND_SUBMITTED, cancellationReason = "Shipment creation failed")
    }

    fun createShipment(shipmentId: Long, trackingNumber: String): Order {
        requireStatus("create shipment", OrderStatus.PAYMENT_CONFIRMED)
        return copy(status = OrderStatus.SHIPMENT_CREATED, shipmentId = shipmentId, trackingNumber = trackingNumber)
    }

    fun startDelivery(shipmentId: Long): Order {
        requireStatus("start delivery", OrderStatus.SHIPMENT_CREATED)
        return copy(status = OrderStatus.IN_DELIVERY, shipmentId = shipmentId)
    }

    fun loseShipment(): Order {
        requireStatus("lose shipment", OrderStatus.IN_DELIVERY)
        return copy(status = OrderStatus.REFUND_SUBMITTED, cancellationReason = "Shipment lost")
    }

    /**
     * Cancelling before the courier picked up the goods (SHIPMENT_CREATED) moves the order
     * straight to REFUND_SUBMITTED, awaiting refund confirmation. Cancelling once the courier
     * already has the goods (IN_DELIVERY) moves the order to IN_RETURN instead - it only reaches
     * REFUND_SUBMITTED once [returnToStock] confirms the goods are physically back in the
     * warehouse. Either way, the order only becomes CANCELLED once [confirmRefund] reports the
     * refund actually went through.
     */
    fun cancelShipment(): Order {
        requireStatus("cancel shipment", OrderStatus.SHIPMENT_CREATED, OrderStatus.IN_DELIVERY)
        val nextStatus = if (status == OrderStatus.IN_DELIVERY) OrderStatus.IN_RETURN else OrderStatus.REFUND_SUBMITTED
        return copy(status = nextStatus, cancellationReason = "Cancelled by customer")
    }

    fun returnToStock(): Order {
        requireStatus("return to stock", OrderStatus.IN_RETURN)
        return copy(status = OrderStatus.REFUND_SUBMITTED)
    }

    /** Payment service confirmed the refund actually went through; this is the only place `refunded` is set. */
    fun confirmRefund(amount: Money): Order {
        requireStatus("confirm refund", OrderStatus.REFUND_SUBMITTED)
        return copy(status = OrderStatus.CANCELLED, refunded = amount)
    }

    fun deliver(): Order {
        requireStatus("deliver", OrderStatus.IN_DELIVERY)
        return copy(status = OrderStatus.FULFILLED)
    }

    private fun requireStatus(action: String, vararg expected: OrderStatus) {
        if (status !in expected) {
            throw DomainException(
                "cannot $action for order $id: expected status ${expected.joinToString()} but was $status",
            )
        }
    }

    companion object {
        fun create(
            userId: Long,
            items: List<OrderItem>,
            address: Address,
        ): Order {
            return Order(
                id = 0,
                userId = userId,
                items = items,
                address = address,
                status = OrderStatus.CREATED,
                createdAt = LocalDateTime.now(),
            )
        }
    }
}

enum class OrderStatus {
    CREATED,
    STOCK_RESERVED,
    PAYMENT_SUBMITTED,
    PAYMENT_CONFIRMED,
    SHIPMENT_CREATED,
    IN_DELIVERY,
    IN_RETURN,
    REFUND_SUBMITTED,
    CANCELLED,
    FULFILLED,
}
