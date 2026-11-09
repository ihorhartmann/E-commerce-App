package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.ConflictException

/**
 * The goods of the order were reported back in stock, but the order doesn't wait for them: it was never cancelled
 * after the courier picked them up, or they were already reported back.
 */
class ReturnNotAwaitedException(val orderId: Long) : ConflictException(
    OrderErrorCode.RETURN_NOT_AWAITED,
    "Order awaits no returned goods",
    mapOf("orderId" to orderId),
)
