package com.hartmann.entities.value

import java.time.Instant

data class ShipmentInfo(
    val trackingNumber: String,
    val pickedUpAt: Instant? = null,
    val deliveredAt: Instant? = null,
    val returnedAt: Instant? = null,
) {
    fun pickUp(): ShipmentInfo {
        check(pickedUpAt == null) { "shipment already picked up" }
        return copy(pickedUpAt = Instant.now())
    }

    fun deliver(): ShipmentInfo {
        check(pickedUpAt != null) { "cannot deliver a shipment that was never picked up" }
        return copy(deliveredAt = Instant.now())
    }

    fun returnToInventory(): ShipmentInfo {
        check(returnedAt == null) { "shipment already marked returned" }
        return copy(returnedAt = Instant.now())
    }

    companion object {
        fun create(trackingNumber: String) = ShipmentInfo(trackingNumber)
    }
}
