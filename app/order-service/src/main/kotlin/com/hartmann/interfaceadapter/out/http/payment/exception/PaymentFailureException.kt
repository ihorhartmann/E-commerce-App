package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * The payment service failed with a 5xx that doesn't ask to be retried. [paymentCode] is payment's own error
 * code, if it sent one.
 */
class PaymentFailureException(val orderId: Long, val status: Int, val paymentCode: String?, cause: Throwable) :
    OrderServiceException(
        PaymentErrorCode.PAYMENT_FAILURE,
        "Payment service failed",
        attributes = mapOf("orderId" to orderId, "status" to status, "paymentCode" to paymentCode),
        cause = cause,
    )
