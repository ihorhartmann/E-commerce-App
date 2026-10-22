package com.hartmann.entities.value.exception

import com.hartmann.entities.exception.ConflictException

/** The shipment was asked to [action] a second time, or before it was picked up. */
class ShipmentTransitionNotAllowedException(val shipmentId: Long, val action: String) : ConflictException(
    ValueErrorCode.SHIPMENT_TRANSITION_NOT_ALLOWED,
    "Shipment transition not allowed",
    mapOf("shipmentId" to shipmentId, "action" to action),
)
