package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.UnavailableException

/** The payment service is up, but the payment gateway behind it is not. */
class PaymentGatewayUnavailableException(val orderId: Long, cause: Throwable) : UnavailableException(
    PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
    "Payment gateway unavailable",
    mapOf("orderId" to orderId),
    cause = cause,
)
