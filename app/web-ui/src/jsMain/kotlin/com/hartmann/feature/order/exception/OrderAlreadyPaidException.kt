package com.hartmann.feature.order.exception

import com.hartmann.core.network.exception.ApiException
import io.ktor.http.HttpStatusCode

/**
 * The customer asked to cancel an unpaid order, but it turned out to be paid - possibly only just. It's no failure of
 * theirs: the order goes on as a paid one, and they can still cancel it, with a refund.
 */
class OrderAlreadyPaidException(val orderId: Long) : ApiException(
    HttpStatusCode.Conflict.value,
    "Order #$orderId is already paid. You can still cancel it - the amount will be refunded.",
    CODE,
) {
    companion object {
        /** The order-service's code for it. */
        const val CODE = "ORDER.PAYMENT_ALREADY_CONFIRMED"
    }
}
