package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.ConflictException
import com.hartmann.entities.value.Money

class RefundAmountMismatchException(val orderId: Long, refunded: Money, val requested: Money) : ConflictException(
    OrderErrorCode.REFUND_AMOUNT_MISMATCH,
    "Refunded amount differs from the requested one",
    mapOf(
        "orderId" to orderId,
        "refundedAmount" to refunded.amount,
        "refundedCurrency" to refunded.currency,
        "requestedAmount" to requested.amount,
        "requestedCurrency" to requested.currency,
    ),
)
