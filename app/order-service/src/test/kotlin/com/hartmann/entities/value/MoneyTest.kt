package com.hartmann.entities.value

import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MoneyTest {

    @Test
    fun `creates money with a non-negative amount`() {
        val money = Money(BigDecimal("10.00"), Currency.USD)

        assertEquals(BigDecimal("10.00"), money.amount)
        assertEquals(Currency.USD, money.currency)
    }

    @Test
    fun `allows a zero amount`() {
        val money = Money(BigDecimal.ZERO, Currency.USD)

        assertEquals(BigDecimal.ZERO, money.amount)
    }

    @Test
    fun `rejects a negative amount`() {
        assertFailsWith<IllegalArgumentException> { Money(BigDecimal("-10.00"), Currency.USD) }
    }
}
