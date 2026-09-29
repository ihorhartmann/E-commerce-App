package com.hartmann.entities.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        val money = Money(BigDecimal.ZERO, Currency.USD)

        assertEquals(BigDecimal("0.00"), money.amount)
    }

    @Test
    fun `constructor - scales an amount with fewer decimal places to two`() {
        val money = Money(BigDecimal("10.5"), Currency.USD)

        assertEquals(BigDecimal("10.50"), money.amount)
    }

    @Test
    fun `constructor - rejects an amount with more than two decimal places`() {
        assertThrows<IllegalArgumentException> { Money(BigDecimal("10.001"), Currency.USD) }
    }

    @Test
    fun `constructor - rejects a negative amount`() {
        assertThrows<IllegalArgumentException> { Money(BigDecimal("-10.00"), Currency.USD) }
    }

    @Test
    fun `equals - is true for the same currency and numerically equal amount`() {
        assertEquals(Money(BigDecimal("10.00"), Currency.USD), Money(BigDecimal("10"), Currency.USD))
    }

    @Test
    fun `equals - is false for a different amount`() {
        assertNotEquals(Money(BigDecimal("10.00"), Currency.USD), Money(BigDecimal("10.01"), Currency.USD))
    }

    @Test
    fun `equals - is false for a different currency`() {
        assertNotEquals(Money(BigDecimal("10.00"), Currency.USD), Money(BigDecimal("10.00"), Currency.EUR))
    }

    @Test
    fun `hashCode - is the same for numerically equal amounts`() {
        assertEquals(Money(BigDecimal("10.00"), Currency.USD).hashCode(), Money(BigDecimal("10"), Currency.USD).hashCode())
    }
}
