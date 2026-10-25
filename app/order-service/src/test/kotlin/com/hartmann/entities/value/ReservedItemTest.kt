package com.hartmann.entities.value

import com.hartmann.entities.value.exception.InvalidReservedItemException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

private fun reservedItem(productId: Long = 1, name: String = "Keyboard", quantity: Int = 1) =
    ReservedItem(productId, name, quantity, Money(BigDecimal("10.00"), Currency.EUR))

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ReservedItemTest {

    @Test
    fun `constructor - rejects a non-positive product id`() {
        assertThrows<InvalidReservedItemException> { reservedItem(productId = 0) }
    }

    @Test
    fun `constructor - rejects a blank name`() {
        assertThrows<InvalidReservedItemException> { reservedItem(name = " ") }
    }

    @Test
    fun `constructor - rejects a non-positive quantity`() {
        assertThrows<InvalidReservedItemException> { reservedItem(quantity = 0) }
    }
}
