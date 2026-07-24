package com.hartmann.entities.model

import com.hartmann.entities.exception.DomainException
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RefundInfo
import com.hartmann.entities.value.ShipmentInfo
import java.time.LocalDateTime
import java.math.BigDecimal

data class Order(
    val id: Long,
    val userId: Long,
    val items: List<OrderItem>,
    val address: Address,
    val status: OrderStatus,
    val paymentConfirmedAt: LocalDateTime? = null,
    val shipment: ShipmentInfo? = null,
    val cancellationReason: String? = null,
    val awaitingReturn: Boolean = false,
    val refund: RefundInfo? = null,
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
        requireStatus("fail reservation", OrderStatus.PLACED)
        return copy(status = OrderStatus.CANCELLED, cancellationReason = "Reservation failed")
    }

    /** Reservation request failed specifically because the requested quantity is not available. */
    fun outOfStock(): Order {
        requireStatus("mark out of stock", OrderStatus.PLACED)
        return copy(status = OrderStatus.CANCELLED, cancellationReason = "Out of stock")
    }

    /** No charge ever succeeded at this point, so there is nothing to refund. */
    fun failPayment(): Order {
        requireStatus("fail payment", OrderStatus.PLACED)
        return copy(status = OrderStatus.CANCELLED, cancellationReason = "Payment failed")
    }

    fun confirmPayment(): Order {
        requireStatus("confirm payment", OrderStatus.PLACED)
        return copy(status = OrderStatus.PROCESSING, paymentConfirmedAt = LocalDateTime.now())
    }

    fun createShipment(trackingNumber: String): Order {
        requireStatus("create shipment", OrderStatus.PROCESSING)
        return copy(shipment = ShipmentInfo.create(trackingNumber))
    }

    fun startDelivery(): Order {
        requireStatus("start delivery", OrderStatus.PROCESSING)
        val currentShipment = requireNotNull(shipment) { "order $id has no shipment to start delivery for" }
        return copy(shipment = currentShipment.pickUp())
    }

    fun deliver(): Order {
        requireStatus("deliver", OrderStatus.PROCESSING)
        val currentShipment = requireNotNull(shipment) { "order $id has no shipment to deliver" }
        return copy(status = OrderStatus.FULFILLED, shipment = currentShipment.deliver())
    }

    /** Shipment was never created, so there's nothing to physically return. */
    fun failShipmentCreation(): Order {
        requireStatus("fail shipment creation", OrderStatus.PROCESSING)
        return copy(
            status = OrderStatus.CANCEL_REQUESTED,
            cancellationReason = "Shipment creation failed",
            awaitingReturn = false
        )
    }

    /** The goods are lost, not coming back - no return to wait for. */
    fun loseShipment(): Order {
        requireStatus("lose shipment", OrderStatus.PROCESSING)
        return copy(status = OrderStatus.CANCEL_REQUESTED, cancellationReason = "Shipment lost", awaitingReturn = false)
    }

    /**
     * Cancelling before the courier picked up the goods moves the order to CANCEL_REQUESTED with
     * nothing left to physically return. Cancelling once the courier already has the goods also
     * moves it to CANCEL_REQUESTED, but flags [awaitingReturn] - [returnToStock] and
     * [confirmRefund] each check whether the other already completed (refund confirmation is
     * typically much faster than the courier's physical trip back) before finalizing to CANCELLED.
     */
    fun cancelShipment(): Order {
        requireStatus("cancel shipment", OrderStatus.PROCESSING)
        return copy(
            status = OrderStatus.CANCEL_REQUESTED,
            cancellationReason = "Cancelled by customer",
            awaitingReturn = wasPickedUp()
        )
    }

    fun requestRefund(): Order {
        requireStatus("request refund", OrderStatus.CANCEL_REQUESTED)
        return copy(refund = RefundInfo.request())
    }

    /** The courier physically returned the goods. Finalizes to CANCELLED if the refund already confirmed. */
    fun returnToStock(): Order {
        requireStatus("return to stock", OrderStatus.CANCEL_REQUESTED)
        val currentShipment = requireNotNull(shipment) { "order $id has no shipment to return" }
        val nextStatus = if (refund?.isConfirmed == true) OrderStatus.CANCELLED else OrderStatus.CANCEL_REQUESTED
        return copy(status = nextStatus, shipment = currentShipment.returnToInventory(), awaitingReturn = false)
    }

    /**
     * Payment service confirmed the refund actually went through. Finalizes to CANCELLED unless
     * this cancellation still needs the goods to physically come back first ([awaitingReturn]) -
     * in that case only the refund is recorded, and [returnToStock] finalizes the order once the
     * goods arrive.
     */
    fun confirmRefund(amount: Money): Order {
        requireStatus("confirm refund", OrderStatus.CANCEL_REQUESTED)
        val currentRefund = requireNotNull(refund) { "order $id has no refund request to confirm" }
        val nextStatus = if (awaitingReturn) OrderStatus.CANCEL_REQUESTED else OrderStatus.CANCELLED
        return copy(status = nextStatus, refund = currentRefund.confirm(amount))
    }

    /** Payment service reported the refund failed; the order stays CANCEL_REQUESTED, flagged for follow-up. */
    fun markRefundFailed(): Order {
        requireStatus("mark refund failed", OrderStatus.CANCEL_REQUESTED)
        val currentRefund = requireNotNull(refund) { "order $id has no refund request to fail" }
        return copy(refund = currentRefund.markFailed())
    }

    fun wasPickedUp(): Boolean {
        return shipment?.pickedUpAt != null
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
                status = OrderStatus.PLACED,
                createdAt = LocalDateTime.now(),
            )
        }
    }
}

enum class OrderStatus {
    PLACED,
    PROCESSING,
    CANCEL_REQUESTED,
    CANCELLED,
    FULFILLED,
}
