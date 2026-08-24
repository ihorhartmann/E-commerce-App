package com.hartmann.entities.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MoneyTest {

    @Test
    fun `constructor - creates money with a non-negative amount`() {
        val amount = BigDecimal("10.00")
        val currency = Currency.USD
        val money = Money(amount, currency)

        assertEquals(amount, money.amount)
        assertEquals(currency, money.currency)
    }

    @Test
    fun `constructor - allows a zero amount`() {
        val amount = BigDecimal.ZERO
        val money = Money(amount, Currency.USD)

        assertEquals(amount, money.amount)
    }

    @Test
    fun `constructor - rejects a negative amount`() {
        assertThrows<IllegalArgumentException> { Money(BigDecimal("-10.00"), Currency.USD) }
    }
}
