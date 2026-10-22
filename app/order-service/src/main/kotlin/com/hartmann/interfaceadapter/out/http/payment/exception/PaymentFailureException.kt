package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.OrderServiceException

/** The payment service failed with a 5xx other than 503. */
class PaymentFailureException(val orderId: Long, val status: Int, cause: Throwable) : OrderServiceException(
    PaymentErrorCode.PAYMENT_FAILURE,
    "Payment service failed",
    attributes = mapOf("orderId" to orderId, "status" to status),
    cause = cause,
)
