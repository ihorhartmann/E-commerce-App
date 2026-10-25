package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.ConflictException

class PaymentRejectedException(val orderId: Long, cause: Throwable) : ConflictException(
    PaymentErrorCode.PAYMENT_REJECTED,
    "Payment rejected",
    mapOf("orderId" to orderId),
    cause = cause,
)
