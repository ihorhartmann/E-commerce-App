package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.ConflictException
import com.hartmann.entities.model.OrderStatus

/** The order was asked to [action] - e.g. confirm payment - in a [status] that doesn't allow it. */
class OrderTransitionNotAllowedException(val orderId: Long, val action: String, val status: OrderStatus) :
    ConflictException(
        OrderErrorCode.ORDER_TRANSITION_NOT_ALLOWED,
        "Order transition not allowed",
        mapOf("orderId" to orderId, "action" to action, "status" to status),
    )
