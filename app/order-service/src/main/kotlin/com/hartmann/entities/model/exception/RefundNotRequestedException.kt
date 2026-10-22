package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.ConflictException

/** The order was asked to [action] before its cancellation was confirmed and a refund requested. */
class RefundNotRequestedException(val orderId: Long, val action: String) : ConflictException(
    OrderErrorCode.REFUND_NOT_REQUESTED,
    "Order has no refund request",
    mapOf("orderId" to orderId, "action" to action),
)
