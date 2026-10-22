package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.ConflictException

/** The order was asked to [action] its shipment before shipping reported the shipment created. */
class ShipmentNotCreatedException(val orderId: Long, val action: String) : ConflictException(
    OrderErrorCode.SHIPMENT_NOT_CREATED,
    "Order has no shipment",
    mapOf("orderId" to orderId, "action" to action),
)
