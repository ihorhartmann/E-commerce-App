package com.hartmann.entities.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun testAddress(
    city: String = "Kyiv",
    postalCode: String = "01001",
    line1: String = "Khreshchatyk 1",
    line2: String = "",
) = Address(city = city, postalCode = postalCode, line1 = line1, line2 = line2)

class AddressTest {

    @Test
    fun `creates an address with all fields set`() {
        val address = testAddress(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "Apt 5")

        assertEquals("Kyiv", address.city)
        assertEquals("01001", address.postalCode)
        assertEquals("Khreshchatyk 1", address.line1)
        assertEquals("Apt 5", address.line2)
    }

    @Test
    fun `rejects a blank city`() {
        assertFailsWith<IllegalArgumentException> { testAddress(city = "") }
    }

    @Test
    fun `rejects a blank postal code`() {
        assertFailsWith<IllegalArgumentException> { testAddress(postalCode = "") }
    }

    @Test
    fun `rejects a blank line1`() {
        assertFailsWith<IllegalArgumentException> { testAddress(line1 = "") }
    }

    @Test
    fun `allows a blank line2`() {
        val address = testAddress(line2 = "")

        assertEquals("", address.line2)
    }
}
