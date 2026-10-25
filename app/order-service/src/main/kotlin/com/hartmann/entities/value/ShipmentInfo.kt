package com.hartmann.entities.value

import com.hartmann.entities.value.exception.ShipmentTransitionNotAllowedException
import java.time.Instant

data class ShipmentInfo(
    val shipmentId: Long,
    val pickedUpAt: Instant? = null,
    val deliveredAt: Instant? = null,
    val returnedAt: Instant? = null,
) {
    fun pickUp(): ShipmentInfo {
        requireAllowed(pickedUpAt == null, "pick up")
        return copy(pickedUpAt = Instant.now())
    }

    fun deliver(): ShipmentInfo {
        requireAllowed(pickedUpAt != null, "deliver")
        return copy(deliveredAt = Instant.now())
    }

    fun returnToInventory(): ShipmentInfo {
        requireAllowed(returnedAt == null, "return to inventory")
        return copy(returnedAt = Instant.now())
    }

    private fun requireAllowed(allowed: Boolean, action: String) {
        if (!allowed) {
            throw ShipmentTransitionNotAllowedException(shipmentId, action)
        }
    }

    companion object {
        fun create(shipmentId: Long) = ShipmentInfo(shipmentId)
    }
}
