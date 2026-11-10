package com.hartmann.feature.order.model

import com.hartmann.core.model.Money

/** Timestamps stay ISO-8601 strings as the API sends them; the screens format them for display. */
data class Order(
    val id: Long,
    val status: OrderStatus,
    val cancellationReason: String?,
    val items: List<OrderItem>,
    val total: Money,
    val address: Address,
    val createdAt: String,
    val paymentConfirmedAt: String?,
    val shipment: ShipmentProgress?,
    val refund: RefundProgress?,
) {
    /** Whether its customer cancelled it - unlike an order cancelled for its payment, delivery or shipment. */
    fun isCancelledByCustomer(): Boolean =
        status == OrderStatus.CANCELLED && cancellationReason == CUSTOMER_CANCELLATION_REASON

    /** Whether it was cancelled because nobody paid it in time - unlike a payment the bank declined. */
    fun isCancelledAsUnpaid(): Boolean =
        status == OrderStatus.CANCELLED && cancellationReason == PAYMENT_EXPIRED_REASON
}

// the order-service's cancellation reasons for an order its customer cancelled, and for one never paid in time
private const val CUSTOMER_CANCELLATION_REASON = "CANCELLED_BY_CUSTOMER"
private const val PAYMENT_EXPIRED_REASON = "PAYMENT_EXPIRED"

data class OrderSummary(
    val id: Long,
    val status: OrderStatus,
    val total: Money,
    val itemCount: Int,
    val createdAt: String,
)

data class OrderItem(val productName: String, val quantity: Int, val price: Money)

data class ShipmentProgress(val pickedUpAt: String?, val deliveredAt: String?)

data class RefundProgress(val requestedAt: String, val amount: Money?, val confirmedAt: String?, val failedAt: String?)

/** A just-placed order: [clientSecret] confirms its Stripe payment, and is only ever returned here. */
data class PlacedOrder(val orderId: Long, val total: Money, val clientSecret: String?)

/** One product line of an order to place. */
data class OrderLine(val productId: Long, val quantity: Int)

enum class OrderStatus(val label: String) {
    PLACED("Awaiting payment"),
    PROCESSING("In progress"),
    CANCEL_REQUESTED("Cancelling"),
    CANCELLED("Cancelled"),
    FULFILLED("Delivered");

    /** Nothing changes an order in a final status anymore, so there's no point polling it. */
    fun isTerminal(): Boolean = this == CANCELLED || this == FULFILLED

    /**
     * A customer can cancel an order that waits for its payment, or a paid one that hasn't been delivered (the
     * order-service's rule).
     */
    fun isCancellable(): Boolean = this == PLACED || this == PROCESSING
}

fun cancellationReasonLabel(reason: String): String = when (reason) {
    "PAYMENT_FAILED" -> "Payment failed"
    "PAYMENT_EXPIRED" -> "Not paid in time"
    "SHIPMENT_LOST" -> "Shipment lost"
    CUSTOMER_CANCELLATION_REASON -> "Cancelled by you"
    "DELIVERY_FAILED" -> "Delivery failed"
    else -> reason
}
