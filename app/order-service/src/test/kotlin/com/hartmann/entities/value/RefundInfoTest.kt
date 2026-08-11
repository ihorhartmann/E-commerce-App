package com.hartmann.entities.value

import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testAmount() = Money(BigDecimal("10.00"), Currency.USD)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RefundInfoTest {

    @Test
    fun `request records the request time with nothing confirmed yet`() {
        val refund = RefundInfo.request()

        assertNotNull(refund.requestedAt)
        assertNull(refund.amount)
        assertFalse(refund.isConfirmed)
    }

    @Test
    fun `confirm records the amount and confirmation time`() {
        val refund = RefundInfo.request().confirm(testAmount())

        assertEquals(testAmount(), refund.amount)
        assertNotNull(refund.confirmedAt)
        assertTrue(refund.isConfirmed)
    }

    @Test
    fun `confirm throws when already confirmed`() {
        val refund = RefundInfo.request().confirm(testAmount())

        assertFailsWith<IllegalStateException> { refund.confirm(testAmount()) }
    }

    @Test
    fun `markFailed records the failure time`() {
        val refund = RefundInfo.request().markFailed()

        assertNotNull(refund.failedAt)
        assertFalse(refund.isConfirmed)
    }

    @Test
    fun `markFailed throws when already confirmed`() {
        val refund = RefundInfo.request().confirm(testAmount())

        assertFailsWith<IllegalStateException> { refund.markFailed() }
    }
}
