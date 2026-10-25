package com.hartmann.entities.value

import com.hartmann.entities.value.exception.UnsupportedCurrencyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CurrencyTest {

    @Test
    fun `fromCode - resolves an uppercase code`() {
        assertEquals(Currency.USD, Currency.fromCode("USD"))
    }

    @Test
    fun `fromCode - resolves a lowercase code`() {
        assertEquals(Currency.EUR, Currency.fromCode("eur"))
    }

    @Test
    fun `fromCode - resolves a mixed-case code`() {
        assertEquals(Currency.GBP, Currency.fromCode("GbP"))
    }

    @Test
    fun `fromCode - throws on an unsupported code`() {
        val exception = assertThrows<UnsupportedCurrencyException> { Currency.fromCode("JPY") }

        assertTrue(exception.cause is IllegalArgumentException)
    }

    @Test
    fun `fromCode - throws on a blank code`() {
        assertThrows<UnsupportedCurrencyException> { Currency.fromCode("") }
    }
}
