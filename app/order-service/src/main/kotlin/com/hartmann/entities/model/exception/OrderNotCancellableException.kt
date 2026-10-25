package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.ConflictException
import com.hartmann.entities.model.OrderStatus

class OrderNotCancellableException(val orderId: Long, val status: OrderStatus) : ConflictException(
    OrderErrorCode.ORDER_NOT_CANCELLABLE,
    "Order can't be cancelled",
    mapOf("orderId" to orderId, "status" to status),
)
