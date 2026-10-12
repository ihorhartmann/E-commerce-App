package com.hartmann.interfaceadapter.`in`.kafka.dto

import com.hartmann.entities.value.ShipmentCancellationReason

data class ShipmentCancelledEvent(val orderId: Long, val shipmentId: Long, val reason: String)

fun fromShipmentCancelledEvent(event: ShipmentCancelledEvent): ShipmentCancellationReason =
    when (event.reason) {
        "OrderCancelled" -> ShipmentCancellationReason.ORDER_CANCELLED
        "RecipientRefused" -> ShipmentCancellationReason.RECIPIENT_REFUSED
        "RecipientUnavailable" -> ShipmentCancellationReason.RECIPIENT_UNAVAILABLE
        else -> throw IllegalArgumentException("unknown shipment cancellation reason ${event.reason}")
    }
