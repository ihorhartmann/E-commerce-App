package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.OrderServiceException

/**
 * The payment service rejected the request with a 4xx other than 409: a request this service shouldn't have
 * sent, so an internal failure, not the caller's.
 */
class PaymentRequestRejectedException(val orderId: Long, val status: Int, cause: Throwable) : OrderServiceException(
    PaymentErrorCode.PAYMENT_REQUEST_REJECTED,
    "Payment service rejected the request",
    attributes = mapOf("orderId" to orderId, "status" to status),
    cause = cause,
)
