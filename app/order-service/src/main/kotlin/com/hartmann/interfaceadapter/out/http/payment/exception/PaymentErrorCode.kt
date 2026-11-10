package com.hartmann.interfaceadapter.out.http.payment.exception

import com.hartmann.entities.exception.ErrorCode

/** Codes of the failures of calls to the payment service. */
enum class PaymentErrorCode(override val title: String) : ErrorCode {
    PAYMENT_REJECTED("Payment was rejected"),
    PAYMENT_ALREADY_CONFIRMED("The order is already paid, so it can only be cancelled with a refund"),
    PAYMENT_GATEWAY_UNAVAILABLE("Payment gateway is temporarily unavailable"),
    PAYMENT_SERVICE_UNAVAILABLE("Payment service is temporarily unavailable"),
    PAYMENT_FAILURE("Internal server error"),
    PAYMENT_REQUEST_REJECTED("Internal server error"),
}
