package com.hartmann.entities.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RequestedItemTest {

    @Test
    fun `constructor - keeps the product and the quantity asked for`() {
        val productId = 11L
        val quantity = 3

        val requestedItem = RequestedItem(productId, quantity)

        assertEquals(productId, requestedItem.productId)
        assertEquals(quantity, requestedItem.quantity)
    }

    @Test
    fun `constructor - rejects a non-positive product id`() {
        assertThrows<IllegalArgumentException> { RequestedItem(productId = 0, quantity = 1) }
    }

    @Test
    fun `constructor - rejects a non-positive quantity`() {
        assertThrows<IllegalArgumentException> { RequestedItem(productId = 1, quantity = 0) }
    }
}
