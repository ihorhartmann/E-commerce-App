package com.hartmann.entities.value

import java.time.LocalDateTime

data class ShipmentInfo(
    val trackingNumber: String,
    val pickedUpAt: LocalDateTime? = null,
    val deliveredAt: LocalDateTime? = null,
    val returnedAt: LocalDateTime? = null,
) {
    fun pickUp(): ShipmentInfo {
        check(pickedUpAt == null) { "shipment already picked up" }
        return copy(pickedUpAt = LocalDateTime.now())
    }

    fun deliver(): ShipmentInfo {
        check(pickedUpAt != null) { "cannot deliver a shipment that was never picked up" }
        return copy(deliveredAt = LocalDateTime.now())
    }

    fun returnToInventory(): ShipmentInfo {
        check(returnedAt == null) { "shipment already marked returned" }
        return copy(returnedAt = LocalDateTime.now())
    }

    companion object {
        fun create(trackingNumber: String) = ShipmentInfo(trackingNumber)
    }
}
