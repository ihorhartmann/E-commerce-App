package com.hartmann.entities.value

import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ShipmentInfoTest {

    @Test
    fun `create sets the tracking number with no other facts recorded yet`() {
        val shipment = ShipmentInfo.create("TRACK-1")

        assertEquals("TRACK-1", shipment.trackingNumber)
        assertNull(shipment.pickedUpAt)
        assertNull(shipment.deliveredAt)
        assertNull(shipment.returnedAt)
    }

    @Test
    fun `pickUp records the pickup time`() {
        val shipment = ShipmentInfo.create("TRACK-1").pickUp()

        assertNotNull(shipment.pickedUpAt)
    }

    @Test
    fun `pickUp throws when already picked up`() {
        val shipment = ShipmentInfo.create("TRACK-1").pickUp()

        assertFailsWith<IllegalStateException> { shipment.pickUp() }
    }

    @Test
    fun `deliver records the delivery time after pickup`() {
        val shipment = ShipmentInfo.create("TRACK-1").pickUp().deliver()

        assertNotNull(shipment.deliveredAt)
    }

    @Test
    fun `deliver throws when never picked up`() {
        val shipment = ShipmentInfo.create("TRACK-1")

        assertFailsWith<IllegalStateException> { shipment.deliver() }
    }

    @Test
    fun `returnToInventory records the return time`() {
        val shipment = ShipmentInfo.create("TRACK-1").pickUp().returnToInventory()

        assertNotNull(shipment.returnedAt)
    }

    @Test
    fun `returnToInventory throws when already returned`() {
        val shipment = ShipmentInfo.create("TRACK-1").pickUp().returnToInventory()

        assertFailsWith<IllegalStateException> { shipment.returnToInventory() }
    }
}
