package com.hartmann.entities.model

import com.hartmann.entities.model.exception.InconsistentOrderException
import com.hartmann.entities.model.exception.OrderInvariantViolatedException
import com.hartmann.entities.model.exception.OrderNotCancellableException
import com.hartmann.entities.model.exception.OrderTransitionNotAllowedException
import com.hartmann.entities.model.exception.RefundAmountMismatchException
import com.hartmann.entities.model.exception.RefundNotRequestedException
import com.hartmann.entities.model.exception.ReturnNotAwaitedException
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

/**
 * An order keeps facts - what has happened to it, each step of the saga leaving its own - and never a status of its
 * own: [status] is what the facts add up to, and every rule asks the facts themselves (is it paid, is its shipment still
 * open), never the status. Events of the saga may come late, twice or out of order, and a fact answers the question a
 * rule actually has, where a status would have to be decoded back into the facts it stands for.
 */
class Order private constructor(
    val id: Long,
    val customerId: UUID,
    val items: List<OrderItem>,
    val address: Address,
    paymentConfirmedAt: Instant?,
    shipment: ShipmentInfo?,
    cancellationReason: CancellationReason?,
    cancellationRequestedAt: Instant?,
    cancelledAt: Instant?,
    awaitingReturn: Boolean,
    refund: RefundInfo?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant?,
) {
    var paymentConfirmedAt: Instant? = paymentConfirmedAt
        private set
    var shipment: ShipmentInfo? = shipment
        private set
    var cancellationReason: CancellationReason? = cancellationReason
        private set

    /** When the order started being cancelled - asked by the customer, or forced by a failed payment or delivery. */
    var cancellationRequestedAt: Instant? = cancellationRequestedAt
        private set

    /** When the cancellation was done: the payment closed unpaid, or the paid amount refunded. */
    var cancelledAt: Instant? = cancelledAt
        private set
    var awaitingReturn: Boolean = awaitingReturn
        private set
    var refund: RefundInfo? = refund
        private set

    /**
     * What the facts add up to, for the customer and for queries - the repository stores it alongside them. A delivery
     * wins over a cancellation: a customer whose cancellation came too late got their goods.
     */
    val status: OrderStatus
        get() = when {
            shipment?.deliveredAt != null -> OrderStatus.FULFILLED
            cancelledAt != null -> OrderStatus.CANCELLED
            cancellationRequestedAt != null -> OrderStatus.CANCEL_REQUESTED
            paymentConfirmedAt != null -> OrderStatus.PROCESSING
            else -> OrderStatus.PLACED
        }

    /** Whether the order was placed by this customer - the only one allowed to see it. */
    fun isOwnedBy(customerId: UUID): Boolean = this.customerId == customerId

    fun totalPrice(): Money {
        val currency: Currency = items.first().price.currency
        val amount = items.fold(BigDecimal.ZERO) { total, item ->
            total + item.price.amount.multiply(item.quantity.toString().toBigDecimal())
        }

        return Money(amount, currency)
    }

    fun isPaid(): Boolean = paymentConfirmedAt != null

    /**
     * Whether the order's payment is still open: neither paid nor closed unpaid. Such an order is cancelled through its
     * payment - payment-service cancels it first, so it can no longer be paid - and only then [requestCancellation].
     */
    fun isPaymentOpen(): Boolean = !isPaid() && cancelledAt == null

    fun confirmPayment() {
        // a customer who asked to cancel it had its payment cancelled first: a confirmation can't follow that
        if (!isPaymentOpen() || cancellationRequestedAt != null) {
            throw OrderTransitionNotAllowedException(id, "confirm payment", status)
        }
        paymentConfirmedAt = Instant.now()
    }

    /** No charge ever succeeded at this point, so there is nothing to refund. */
    fun failPayment() = cancelUnpaid("fail payment", CancellationReason.PAYMENT_FAILED)

    /** Nobody ever completed checkout - payment-service gave up waiting and expired the attempt. */
    fun expirePayment() = cancelUnpaid("expire payment", CancellationReason.PAYMENT_EXPIRED)

    /**
     * The customer cancelled the order before paying it, and payment-service cancelled its payment: nothing was charged,
     * so there is nothing to refund - only the reserved stock to release.
     */
    fun cancelPayment() = cancelUnpaid("cancel payment", CancellationReason.CANCELLED_BY_CUSTOMER)

    /**
     * The customer asks to cancel the order. An order still unpaid is asked only once payment-service has cancelled its
     * payment, which then finishes the cancellation ([cancelPayment]). A paid one waits for shipping-service to
     * answer what actually happened to the shipment - it may have been delivered or lost in the meantime; nothing is
     * refunded or released until then. Returns whether anything changed: asking again while the order is already being
     * cancelled, or is cancelled, changes nothing. A delivered order can no longer be cancelled.
     */
    fun requestCancellation(): Boolean {
        if (cancellationRequestedAt != null) {
            return false
        }
        if (shipment?.deliveredAt != null) {
            throw OrderNotCancellableException(id, status)
        }
        cancellationReason = CancellationReason.CANCELLED_BY_CUSTOMER
        cancellationRequestedAt = Instant.now()
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
        cancellationReason = null
        cancellationRequestedAt = null
        shipment = deliveredShipment
    }

    /**
     * The goods are lost, not coming back - no return to wait for. The order was already paid, so a
     * refund of [refundAmount] is requested right away.
     */
    fun loseShipment() {
        requireOpenShipment("lose shipment")
        cancellationReason = CancellationReason.SHIPMENT_LOST
        cancellationRequestedAt = cancellationRequestedAt ?: Instant.now()
        awaitingReturn = false
        refund = RefundInfo.request()
    }

    /**
     * Shipping-service cancelled the shipment, for [shipmentCancellationReason]. If the customer had asked for it,
     * this confirms their request, whatever the shipment was actually cancelled for - the courier may have
     * cancelled it on their own just before. Otherwise the courier couldn't hand the goods over, and the reason
     * tells whether the customer refused them.
     *
     * Either way the order is being cancelled and, as it was already paid, a refund of [refundAmount] is requested
     * right away; [confirmRefund] finishes the cancellation. If the courier already has the goods, the order also flags
     * [awaitingReturn]: their trip back to the warehouse is logistics, recorded by [returnToStock], and doesn't hold
     * the customer's order back.
     */
    fun cancelShipment(shipmentCancellationReason: ShipmentCancellationReason) {
        requireOpenShipment("cancel shipment")
        val reason = if (cancellationRequestedAt != null) {
            // the customer asked for it: shipping only confirms, whatever its own reason
            CancellationReason.CANCELLED_BY_CUSTOMER
        } else {
            reasonOfCourierCancellation(shipmentCancellationReason)
        }
        cancellationReason = reason
        cancellationRequestedAt = cancellationRequestedAt ?: Instant.now()
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
     * The courier physically returned the goods of a cancelled shipment to the warehouse. Only the shipment records
     * it: the order's status follows the customer's side - the refund - not where the goods are, so it's whatever
     * [confirmRefund] made it. Reporting goods the order doesn't wait for, or reporting them twice, is rejected.
     */
    fun returnToStock() {
        if (!awaitingReturn) {
            throw ReturnNotAwaitedException(id)
        }
        val currentShipment = requireShipment("return to stock")
        shipment = currentShipment.returnToInventory()
        awaitingReturn = false
    }

    /**
     * Payment service confirmed the refund actually went through, which finishes the cancellation - even if the goods
     * are still on their way back to the warehouse ([awaitingReturn]): that's no concern of the customer's. The
     * refunded amount must be the requested [refundAmount].
     */
    fun confirmRefund(amount: Money) {
        val currentRefund = requireRefund("confirm refund")
        requireNotCancelled("confirm refund")
        if (amount != refundAmount()) {
            throw RefundAmountMismatchException(id, amount, refundAmount())
        }
        refund = currentRefund.confirm(amount)
        cancelledAt = Instant.now()
    }

    /** Payment service reported the refund failed; the order stays being cancelled, flagged for follow-up. */
    fun failRefund() {
        val currentRefund = requireRefund("fail refund")
        requireNotCancelled("fail refund")
        refund = currentRefund.fail()
    }

    fun wasPickedUp(): Boolean {
        return shipment?.pickedUpAt != null
    }

    /**
     * Cancels an order whose payment closed unpaid - failed, expired or cancelled. A customer who had asked to cancel it
     * keeps their reason: whatever closed the payment, the order is cancelled the way they asked for.
     */
    private fun cancelUnpaid(action: String, reason: CancellationReason) {
        if (!isPaymentOpen()) {
            throw OrderTransitionNotAllowedException(id, action, status)
        }
        val now = Instant.now()
        if (cancellationRequestedAt == null) {
            cancellationReason = reason
            cancellationRequestedAt = now
        }
        cancelledAt = now
    }

    /**
     * The shipment can still end either way: the order is paid, not delivered, and no refund has been requested yet -
     * if the customer asked to cancel it, shipping-service hasn't answered, and events about what the courier did
     * before the request reached it may still arrive. Once a refund is requested, the shipment is closed.
     */
    private fun requireOpenShipment(action: String) {
        if (!isPaid() || shipment?.deliveredAt != null || refund != null) {
            throw OrderTransitionNotAllowedException(id, action, status)
        }
    }

    private fun requireNotCancelled(action: String) {
        if (cancelledAt != null) {
            throw OrderTransitionNotAllowedException(id, action, status)
        }
    }

    private fun requireShipment(action: String): ShipmentInfo = shipment ?: throw ShipmentNotCreatedException(id, action)

    private fun requireRefund(action: String): RefundInfo = refund ?: throw RefundNotRequestedException(id, action)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Order) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Order(id=$id, customerId=$customerId, status=$status, paymentConfirmedAt=$paymentConfirmedAt, " +
                "cancellationReason=$cancellationReason, cancellationRequestedAt=$cancellationRequestedAt, " +
                "cancelledAt=$cancelledAt, awaitingReturn=$awaitingReturn, shipment=$shipment, refund=$refund, " +
                "version=$version)"

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
                paymentConfirmedAt = null,
                shipment = null,
                cancellationReason = null,
                cancellationRequestedAt = null,
                cancelledAt = null,
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
            paymentConfirmedAt: Instant? = null,
            shipment: ShipmentInfo? = null,
            cancellationReason: CancellationReason? = null,
            cancellationRequestedAt: Instant? = null,
            cancelledAt: Instant? = null,
            awaitingReturn: Boolean = false,
            refund: RefundInfo? = null,
            version: Long = 0,
            createdAt: Instant,
            updatedAt: Instant? = null,
        ): Order {
            findBrokenRule(id, items)?.let { throw InconsistentOrderException(id, it) }
            findBrokenFact(paymentConfirmedAt, shipment, cancellationReason, cancellationRequestedAt, cancelledAt, refund)
                ?.let { throw InconsistentOrderException(id, it) }
            return Order(
                id = id,
                customerId = customerId,
                items = items,
                address = address,
                paymentConfirmedAt = paymentConfirmedAt,
                shipment = shipment,
                cancellationReason = cancellationReason,
                cancellationRequestedAt = cancellationRequestedAt,
                cancelledAt = cancelledAt,
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

        /**
         * The first rule a stored order's facts break, or null: the facts only ever arise in an order the transitions
         * allow, so any other combination is corrupted data. A new order has no facts yet.
         */
        private fun findBrokenFact(
            paymentConfirmedAt: Instant?,
            shipment: ShipmentInfo?,
            cancellationReason: CancellationReason?,
            cancellationRequestedAt: Instant?,
            cancelledAt: Instant?,
            refund: RefundInfo?,
        ): String? = when {
            (cancellationReason == null) != (cancellationRequestedAt == null) ->
                "a cancellation must have both its reason and its request time"
            cancelledAt != null && cancellationRequestedAt == null -> "a cancelled order must have been asked to cancel"
            shipment != null && paymentConfirmedAt == null -> "a shipment needs a paid order"
            refund != null && (paymentConfirmedAt == null || cancellationRequestedAt == null) ->
                "a refund needs a paid order being cancelled"
            paymentConfirmedAt != null && cancelledAt != null && refund?.isConfirmed != true ->
                "a paid order is cancelled only by its confirmed refund"
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
