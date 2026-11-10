package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.ConflictException

/**
 * The customer asked to cancel an unpaid order, but payment-service found its payment confirmed - possibly only just,
 * the gateway telling so instead of cancelling it. The order is cancelled the way any paid order is, with a refund,
 * once payment-service's PaymentCompleted has moved it on.
 */
class PaymentAlreadyConfirmedException(val orderId: Long, cause: Throwable) : ConflictException(
    PaymentErrorCode.PAYMENT_ALREADY_CONFIRMED,
    "Payment already confirmed",
    mapOf("orderId" to orderId),
    cause = cause,
)
