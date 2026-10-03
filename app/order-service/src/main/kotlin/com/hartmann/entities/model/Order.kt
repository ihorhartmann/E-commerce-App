package com.hartmann.entities.model

import com.hartmann.entities.exception.DomainException
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RefundInfo
import com.hartmann.entities.value.ShipmentInfo
import java.time.Instant
import java.math.BigDecimal

class Order private constructor(
    val id: Long,
    val userId: Long,
    val items: List<OrderItem>,
    val address: Address,
    status: OrderStatus,
    paymentConfirmedAt: Instant?,
    shipment: ShipmentInfo?,
    cancellationReason: CancellationReason?,
    awaitingReturn: Boolean,
    refund: RefundInfo?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant?,
) {
    var status: OrderStatus = status
        private set
    var paymentConfirmedAt: Instant? = paymentConfirmedAt
        private set
    var shipment: ShipmentInfo? = shipment
        private set
    var cancellationReason: CancellationReason? = cancellationReason
        private set
    var awaitingReturn: Boolean = awaitingReturn
        private set
    var refund: RefundInfo? = refund
        private set

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
    fun failReservation() {
        requireStatus("fail reservation", OrderStatus.PLACED)
        cancel(CancellationReason.RESERVATION_FAILED)
    }

    /** Reservation request failed specifically because the requested quantity is not available. */
    fun outOfStock() {
        requireStatus("mark out of stock", OrderStatus.PLACED)
        cancel(CancellationReason.OUT_OF_STOCK)
    }

    /** No charge ever succeeded at this point, so there is nothing to refund. */
    fun failPayment() {
        requireStatus("fail payment", OrderStatus.PLACED)
        cancel(CancellationReason.PAYMENT_FAILED)
    }

    /** Nobody ever completed checkout - payment-service gave up waiting and expired the attempt. */
    fun expirePayment() {
        requireStatus("expire payment", OrderStatus.PLACED)
        cancel(CancellationReason.PAYMENT_EXPIRED)
    }

    fun confirmPayment() {
        requireStatus("confirm payment", OrderStatus.PLACED)
        status = OrderStatus.PROCESSING
        paymentConfirmedAt = Instant.now()
    }

    fun createShipment(trackingNumber: String) {
        requireStatus("create shipment", OrderStatus.PROCESSING)
        shipment = ShipmentInfo.create(trackingNumber)
    }

    fun startDelivery() {
        requireStatus("start delivery", OrderStatus.PROCESSING)
        val currentShipment = requireNotNull(shipment) { "order $id has no shipment to start delivery for" }
        shipment = currentShipment.pickUp()
    }

    fun deliver() {
        requireStatus("deliver", OrderStatus.PROCESSING)
        val currentShipment = requireNotNull(shipment) { "order $id has no shipment to deliver" }
        val deliveredShipment = currentShipment.deliver()
        status = OrderStatus.FULFILLED
        shipment = deliveredShipment
    }

    /**
     * The goods are lost, not coming back - no return to wait for. The order was already paid, so a
     * refund of [refundAmount] is requested right away.
     */
    fun loseShipment() {
        requireStatus("lose shipment", OrderStatus.PROCESSING)
        status = OrderStatus.CANCEL_REQUESTED
        cancellationReason = CancellationReason.SHIPMENT_LOST
        awaitingReturn = false
        refund = RefundInfo.request()
    }

    /**
     * Cancelling before the courier picked up the goods moves the order to CANCEL_REQUESTED with
     * nothing left to physically return. Cancelling once the courier already has the goods also
     * moves it to CANCEL_REQUESTED, but flags [awaitingReturn] - [returnToStock] and
     * [confirmRefund] each check whether the other already completed (refund confirmation is
     * typically much faster than the courier's physical trip back) before finalizing to CANCELLED.
     * Either way the order was already paid, so a refund of [refundAmount] is requested right away.
     */
    fun cancelShipment() {
        requireStatus("cancel shipment", OrderStatus.PROCESSING)
        status = OrderStatus.CANCEL_REQUESTED
        cancellationReason = CancellationReason.CANCELLED_BY_CUSTOMER
        awaitingReturn = wasPickedUp()
        refund = RefundInfo.request()
    }

    /** A cancelled order is refunded in full - everything the customer paid for its items. */
    fun refundAmount(): Money = totalPrice()

    /**
     * Whether this order is cancelled while its stock is still reserved in inventory, so the reservation
     * must be released. Stock is reserved only if the reservation succeeded, and stays reserved until pickup.
     */
    fun requiresReservationRelease(): Boolean = when (cancellationReason) {
        null, CancellationReason.RESERVATION_FAILED, CancellationReason.OUT_OF_STOCK -> false
        else -> !wasPickedUp()
    }

    /** The courier physically returned the goods. Finalizes to CANCELLED if the refund already confirmed. */
    fun returnToStock() {
        requireStatus("return to stock", OrderStatus.CANCEL_REQUESTED)
        val currentShipment = requireNotNull(shipment) { "order $id has no shipment to return" }
        val returnedShipment = currentShipment.returnToInventory()
        if (refund?.isConfirmed == true) {
            status = OrderStatus.CANCELLED
        }
        shipment = returnedShipment
        awaitingReturn = false
    }

    /**
     * Payment service confirmed the refund actually went through. Finalizes to CANCELLED unless
     * this cancellation still needs the goods to physically come back first ([awaitingReturn]) -
     * in that case only the refund is recorded, and [returnToStock] finalizes the order once the
     * goods arrive. The refunded amount must be the requested [refundAmount].
     */
    fun confirmRefund(amount: Money) {
        requireStatus("confirm refund", OrderStatus.CANCEL_REQUESTED)
        val currentRefund = requireNotNull(refund) { "order $id has no refund request to confirm" }
        if (amount != refundAmount()) {
            throw DomainException("refunded $amount for order $id, but ${refundAmount()} was requested")
        }
        val confirmedRefund = currentRefund.confirm(amount)
        if (!awaitingReturn) {
            status = OrderStatus.CANCELLED
        }
        refund = confirmedRefund
    }

    /** Payment service reported the refund failed; the order stays CANCEL_REQUESTED, flagged for follow-up. */
    fun markRefundFailed() {
        requireStatus("mark refund failed", OrderStatus.CANCEL_REQUESTED)
        val currentRefund = requireNotNull(refund) { "order $id has no refund request to fail" }
        refund = currentRefund.markFailed()
    }

    fun wasPickedUp(): Boolean {
        return shipment?.pickedUpAt != null
    }

    private fun cancel(reason: CancellationReason) {
        status = OrderStatus.CANCELLED
        cancellationReason = reason
    }

    private fun requireStatus(action: String, vararg expected: OrderStatus) {
        if (status !in expected) {
            throw DomainException(
                "cannot $action for order $id: expected status ${expected.joinToString()} but was $status",
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Order) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Order(id=$id, userId=$userId, status=$status, cancellationReason=$cancellationReason, " +
                "awaitingReturn=$awaitingReturn, shipment=$shipment, refund=$refund, version=$version)"

    companion object {
        fun createNew(userId: Long, items: List<OrderItem>, address: Address): Order {
            return Order(
                id = 0,
                userId = userId,
                items = items,
                address = address,
                status = OrderStatus.PLACED,
                paymentConfirmedAt = null,
                shipment = null,
                cancellationReason = null,
                awaitingReturn = false,
                refund = null,
                version = 0,
                createdAt = Instant.now(),
                updatedAt = null,
            )
        }

        fun createExisting(
            id: Long,
            userId: Long,
            items: List<OrderItem>,
            address: Address,
            status: OrderStatus,
            paymentConfirmedAt: Instant? = null,
            shipment: ShipmentInfo? = null,
            cancellationReason: CancellationReason? = null,
            awaitingReturn: Boolean = false,
            refund: RefundInfo? = null,
            version: Long = 0,
            createdAt: Instant,
            updatedAt: Instant? = null,
        ): Order {
            require(id > 0) { "id must be positive" }
            return Order(
                id = id,
                userId = userId,
                items = items,
                address = address,
                status = status,
                paymentConfirmedAt = paymentConfirmedAt,
                shipment = shipment,
                cancellationReason = cancellationReason,
                awaitingReturn = awaitingReturn,
                refund = refund,
                version = version,
                createdAt = createdAt,
                updatedAt = updatedAt,
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

enum class CancellationReason {
    RESERVATION_FAILED,
    OUT_OF_STOCK,
    PAYMENT_FAILED,
    PAYMENT_EXPIRED,
    SHIPMENT_LOST,
    CANCELLED_BY_CUSTOMER,
}
