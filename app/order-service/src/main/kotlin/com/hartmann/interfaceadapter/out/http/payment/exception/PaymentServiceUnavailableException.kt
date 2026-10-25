package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.UnavailableException

/** The payment service is unreachable, its circuit breaker is open, or it asked to be retried later. */
class PaymentServiceUnavailableException(val orderId: Long, cause: Throwable) : UnavailableException(
    PaymentErrorCode.PAYMENT_SERVICE_UNAVAILABLE,
    "Payment service unavailable",
    mapOf("orderId" to orderId),
    cause = cause,
)
