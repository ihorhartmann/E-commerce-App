package com.hartmann.entities.model

import com.hartmann.entities.model.exception.InconsistentOrderException
import com.hartmann.entities.model.exception.OrderInvariantViolatedException
import com.hartmann.entities.model.exception.OrderNotCancellableException
import com.hartmann.entities.model.exception.OrderTransitionNotAllowedException
import com.hartmann.entities.model.exception.RefundAmountMismatchException
import com.hartmann.entities.model.exception.RefundNotRequestedException
import com.hartmann.entities.model.exception.ShipmentNotCreatedException
import com.hartmann.entities.model.exception.UnrequestedShipmentCancellationException
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RefundInfo
import com.hartmann.entities.value.ReservedItem
import com.hartmann.entities.value.ShipmentCancellationReason
import com.hartmann.entities.value.ShipmentInfo
import java.time.Instant
import java.math.BigDecimal
import java.util.UUID

class Order private constructor(
    val id: Long,
    val customerId: UUID,
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

    /** Whether the order was placed by this customer - the only one allowed to see it. */
    fun isOwnedBy(customerId: UUID): Boolean = this.customerId == customerId

    fun totalPrice(): Money {
        val currency: Currency = items.first().price.currency
        val amount = items.fold(BigDecimal.ZERO) { total, item ->
            total + item.price.amount.multiply(item.quantity.toString().toBigDecimal())
        }

        return Money(amount, currency)
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

    /**
     * The customer asks to cancel the order. Only a paid order whose shipment is still open can be cancelled: the
     * request moves it to CANCEL_REQUESTED, and shipping-service then answers what actually happened to the
     * shipment - it may have been delivered or lost in the meantime. Nothing is refunded or released until then.
     * Returns whether anything changed: asking again while a cancellation is already under way changes nothing.
     */
    fun requestCancellation(): Boolean {
        if (status == OrderStatus.CANCEL_REQUESTED) {
            return false
        }
        if (status != OrderStatus.PROCESSING) {
            throw OrderNotCancellableException(id, status)
        }
        status = OrderStatus.CANCEL_REQUESTED
        cancellationReason = CancellationReason.CANCELLED_BY_CUSTOMER
        return true
    }

    fun createShipment(shipmentId: Long) {
        requireOpenShipment("create shipment")
        shipment = ShipmentInfo.create(shipmentId)
    }

    fun startDelivery() {
        requireOpenShipment("start delivery")
        val currentShipment = requireShipment("start delivery")
        shipment = currentShipment.pickUp()
    }

    /**
     * The customer got the goods. If they had asked to cancel in the meantime, the request came too late: the
     * order is fulfilled, and no longer cancelled for any reason.
     */
    fun deliver() {
        requireOpenShipment("deliver")
        val currentShipment = requireShipment("deliver")
        val deliveredShipment = currentShipment.deliver()
        status = OrderStatus.FULFILLED
        cancellationReason = null
        shipment = deliveredShipment
    }

    /**
     * The goods are lost, not coming back - no return to wait for. The order was already paid, so a
     * refund of [refundAmount] is requested right away.
     */
    fun loseShipment() {
        requireOpenShipment("lose shipment")
        status = OrderStatus.CANCEL_REQUESTED
        cancellationReason = CancellationReason.SHIPMENT_LOST
        awaitingReturn = false
        refund = RefundInfo.request()
    }

    /**
     * Shipping-service cancelled the shipment, for [shipmentCancellationReason]. If the customer had asked for it,
     * this confirms their request, whatever the shipment was actually cancelled for - the courier may have
     * cancelled it on their own just before. Otherwise the courier couldn't hand the goods over, and the reason
     * tells whether the customer refused them.
     *
     * Cancelling before the courier picked up the goods moves the order to CANCEL_REQUESTED with
     * nothing left to physically return. Cancelling once the courier already has the goods also
     * moves it to CANCEL_REQUESTED, but flags [awaitingReturn] - [returnToStock] and
     * [confirmRefund] each check whether the other already completed (refund confirmation is
     * typically much faster than the courier's physical trip back) before finalizing to CANCELLED.
     * Either way the order was already paid, so a refund of [refundAmount] is requested right away.
     */
    fun cancelShipment(shipmentCancellationReason: ShipmentCancellationReason) {
        requireOpenShipment("cancel shipment")
        val reason = if (status == OrderStatus.CANCEL_REQUESTED) {
            // the customer asked for it: shipping only confirms, whatever its own reason
            CancellationReason.CANCELLED_BY_CUSTOMER
        } else {
            reasonOfCourierCancellation(shipmentCancellationReason)
        }
        status = OrderStatus.CANCEL_REQUESTED
        cancellationReason = reason
        awaitingReturn = wasPickedUp()
        refund = RefundInfo.request()
    }

    /**
     * Why the order is cancelled when nobody asked to cancel it: only the courier can have cancelled the shipment
     * then. Shipping reports ORDER_CANCELLED only in answer to this order's own request, so getting it here means
     * the two services disagree - the event is rejected rather than refunding an order nobody cancelled.
     */
    private fun reasonOfCourierCancellation(shipmentCancellationReason: ShipmentCancellationReason): CancellationReason =
        when (shipmentCancellationReason) {
            ShipmentCancellationReason.RECIPIENT_REFUSED -> CancellationReason.CANCELLED_BY_CUSTOMER
            ShipmentCancellationReason.RECIPIENT_UNAVAILABLE -> CancellationReason.DELIVERY_FAILED
            ShipmentCancellationReason.ORDER_CANCELLED ->
                throw UnrequestedShipmentCancellationException(id)
        }

    /** A cancelled order is refunded in full - everything the customer paid for its items. */
    fun refundAmount(): Money = totalPrice()

    /**
     * Whether this order is cancelled while its stock is still reserved in inventory, so the reservation
     * must be released. An order exists only once its stock is reserved, and the stock stays reserved until pickup.
     */
    fun requiresReservationRelease(): Boolean = cancellationReason != null && !wasPickedUp()

    /**
     * The courier physically returned the goods, which only happens once the shipment's cancellation is confirmed.
     * Finalizes to CANCELLED if the refund already confirmed.
     */
    fun returnToStock() {
        requireStatus("return to stock", OrderStatus.CANCEL_REQUESTED)
        val currentRefund = requireRefund("return to stock")
        val currentShipment = requireShipment("return to stock")
        val returnedShipment = currentShipment.returnToInventory()
        if (currentRefund.isConfirmed) {
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
        val currentRefund = requireRefund("confirm refund")
        if (amount != refundAmount()) {
            throw RefundAmountMismatchException(id, amount, refundAmount())
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
        val currentRefund = requireRefund("mark refund failed")
        refund = currentRefund.markFailed()
    }

    fun wasPickedUp(): Boolean {
        return shipment?.pickedUpAt != null
    }

    /**
     * The shipment can still end either way: the order is being processed, or the customer asked to cancel it and
     * shipping-service hasn't answered yet - events about what the courier did before the request reached it may
     * still arrive. Once a refund is requested, the cancellation is confirmed and the shipment is closed.
     */
    private fun requireOpenShipment(action: String) {
        val awaitingShipmentCancellation = status == OrderStatus.CANCEL_REQUESTED && refund == null
        if (status != OrderStatus.PROCESSING && !awaitingShipmentCancellation) {
            throw OrderTransitionNotAllowedException(id, action, status)
        }
    }

    private fun requireShipment(action: String): ShipmentInfo = shipment ?: throw ShipmentNotCreatedException(id, action)

    private fun requireRefund(action: String): RefundInfo = refund ?: throw RefundNotRequestedException(id, action)

    private fun cancel(reason: CancellationReason) {
        status = OrderStatus.CANCELLED
        cancellationReason = reason
    }

    private fun requireStatus(action: String, vararg expected: OrderStatus) {
        if (status !in expected) {
            throw OrderTransitionNotAllowedException(id, action, status)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Order) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Order(id=$id, customerId=$customerId, status=$status, cancellationReason=$cancellationReason, " +
                "awaitingReturn=$awaitingReturn, shipment=$shipment, refund=$refund, version=$version)"

    companion object {
        /**
         * A new order exists only once its stock is reserved: [id] is taken before the reservation, which is made
         * for it, and its items are exactly the [reservedItems] that come back from that reservation.
         */
        fun createNew(id: Long, customerId: UUID, reservedItems: List<ReservedItem>, address: Address): Order {
            val items = reservedItems.map { OrderItem.createNew(it.productId, it.name, it.quantity, it.price) }
            findBrokenRule(id, items)?.let { throw OrderInvariantViolatedException(it, mapOf("orderId" to id)) }
            return Order(
                id = id,
                customerId = customerId,
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
            customerId: UUID,
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
            findBrokenRule(id, items)?.let { throw InconsistentOrderException(id, it) }
            return Order(
                id = id,
                customerId = customerId,
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

        /**
         * The first of the order's own rules [id] and [items] break, or null. The same rules hold for a new and for a
         * stored order; only what breaking them means differs - a bug in [createNew], corrupted data in
         * [createExisting].
         */
        private fun findBrokenRule(id: Long, items: List<OrderItem>): String? = when {
            id <= 0 -> "id must be positive"
            items.isEmpty() -> "order must contain at least one item"
            items.map { it.price.currency }.distinct().size != 1 -> "all order item currencies must match"
            else -> null
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
    PAYMENT_FAILED,
    PAYMENT_EXPIRED,
    SHIPMENT_LOST,
    CANCELLED_BY_CUSTOMER,
    DELIVERY_FAILED,
}
