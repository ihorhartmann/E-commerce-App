package com.hartmann.entities.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ShipmentInfoTest {

    @Test
    fun `create - sets the tracking number with no other facts recorded yet`() {
        val shipmentId = 5L
        val shipment = ShipmentInfo.create(shipmentId)

        assertEquals(shipmentId, shipment.shipmentId)
        assertNull(shipment.pickedUpAt)
        assertNull(shipment.deliveredAt)
        assertNull(shipment.returnedAt)
    }

    @Test
    fun `pickUp - records the pickup time`() {
        val shipment = ShipmentInfo.create(5L).pickUp()

        assertNotNull(shipment.pickedUpAt)
    }

    @Test
    fun `pickUp - throws when already picked up`() {
        val shipment = ShipmentInfo.create(5L).pickUp()

        assertThrows<IllegalStateException> { shipment.pickUp() }
    }

    @Test
    fun `deliver - records the delivery time after pickup`() {
        val shipment = ShipmentInfo.create(5L).pickUp().deliver()

        assertNotNull(shipment.deliveredAt)
    }

    @Test
    fun `deliver - throws when never picked up`() {
        val shipment = ShipmentInfo.create(5L)

        assertThrows<IllegalStateException> { shipment.deliver() }
    }

    @Test
    fun `returnToInventory - records the return time`() {
        val shipment = ShipmentInfo.create(5L).pickUp().returnToInventory()

        assertNotNull(shipment.returnedAt)
    }

    @Test
    fun `returnToInventory - throws when already returned`() {
        val shipment = ShipmentInfo.create(5L).pickUp().returnToInventory()

        assertThrows<IllegalStateException> { shipment.returnToInventory() }
    }
}
