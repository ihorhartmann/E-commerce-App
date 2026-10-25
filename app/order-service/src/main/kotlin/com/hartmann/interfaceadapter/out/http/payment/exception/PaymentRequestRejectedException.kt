package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * The payment service rejected the request with a 4xx this service doesn't expect: a request this service
 * shouldn't have sent, so an internal failure, not the caller's. [paymentCode] is payment's own error code,
 * if it sent one.
 */
class PaymentRequestRejectedException(
    val orderId: Long,
    val status: Int,
    val paymentCode: String?,
    cause: Throwable,
) : OrderServiceException(
    PaymentErrorCode.PAYMENT_REQUEST_REJECTED,
    "Payment service rejected the request",
    attributes = mapOf("orderId" to orderId, "status" to status, "paymentCode" to paymentCode),
    cause = cause,
)
