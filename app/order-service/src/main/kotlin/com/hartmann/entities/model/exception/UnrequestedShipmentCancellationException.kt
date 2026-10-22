package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.ConflictException

/**
 * Shipping reported the order's shipment cancelled for the order's own sake, which it does only in answer to a
 * cancellation the order requested - but nobody requested one: the two services disagree.
 */
class UnrequestedShipmentCancellationException(val orderId: Long) : ConflictException(
    OrderErrorCode.UNREQUESTED_SHIPMENT_CANCELLATION,
    "Shipment cancelled for an order nobody asked to cancel",
    mapOf("orderId" to orderId),
)
