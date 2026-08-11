package com.hartmann.entities.value

import com.hartmann.entities.exception.DomainException
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CurrencyTest {

    @Test
    fun `fromCode resolves an uppercase code`() {
        assertEquals(Currency.USD, Currency.fromCode("USD"))
    }

    @Test
    fun `fromCode resolves a lowercase code`() {
        assertEquals(Currency.EUR, Currency.fromCode("eur"))
    }

    @Test
    fun `fromCode resolves a mixed-case code`() {
        assertEquals(Currency.GBP, Currency.fromCode("GbP"))
    }

    @Test
    fun `fromCode throws on an unsupported code`() {
        assertFailsWith<DomainException> { Currency.fromCode("JPY") }
    }

    @Test
    fun `fromCode throws on a blank code`() {
        assertFailsWith<DomainException> { Currency.fromCode("") }
    }
}
