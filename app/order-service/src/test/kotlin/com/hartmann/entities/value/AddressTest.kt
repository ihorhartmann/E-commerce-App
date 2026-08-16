package com.hartmann.entities.value

import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun testAddress(
    city: String = "Kyiv",
    postalCode: String = "01001",
    line1: String = "Khreshchatyk 1",
    line2: String = "",
) = Address(city = city, postalCode = postalCode, line1 = line1, line2 = line2)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class AddressTest {

    @Test
    fun `constructor - creates an address with all fields set`() {
        val city = "Kyiv"
        val postalCode = "01001"
        val line1 = "Khreshchatyk 1"
        val line2 = "Apt 5"
        val address = testAddress(city = city, postalCode = postalCode, line1 = line1, line2 = line2)

        assertEquals(city, address.city)
        assertEquals(postalCode, address.postalCode)
        assertEquals(line1, address.line1)
        assertEquals(line2, address.line2)
    }

    @Test
    fun `constructor - rejects a blank city`() {
        assertFailsWith<IllegalArgumentException> { testAddress(city = "") }
    }

    @Test
    fun `constructor - rejects a blank postal code`() {
        assertFailsWith<IllegalArgumentException> { testAddress(postalCode = "") }
    }

    @Test
    fun `constructor - rejects a blank line1`() {
        assertFailsWith<IllegalArgumentException> { testAddress(line1 = "") }
    }

    @Test
    fun `constructor - allows a blank line2`() {
        val address = testAddress(line2 = "")

        assertEquals("", address.line2)
    }
}
