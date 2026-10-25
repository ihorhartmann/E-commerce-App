package com.hartmann.entities.value

import com.hartmann.entities.value.exception.RefundTransitionNotAllowedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

private fun testAmount() = Money(BigDecimal("10.00"), Currency.EUR)

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RefundInfoTest {

    @Test
    fun `request - records the request time with nothing confirmed yet`() {
        val refund = RefundInfo.request()

        assertNotNull(refund.requestedAt)
        assertNull(refund.amount)
        assertFalse(refund.isConfirmed)
    }

    @Test
    fun `confirm - records the amount and confirmation time`() {
        val refund = RefundInfo.request().confirm(testAmount())

        assertEquals(testAmount(), refund.amount)
        assertNotNull(refund.confirmedAt)
        assertTrue(refund.isConfirmed)
    }

    @Test
    fun `confirm - throws when already confirmed`() {
        val refund = RefundInfo.request().confirm(testAmount())

        assertThrows<RefundTransitionNotAllowedException> { refund.confirm(testAmount()) }
    }

    @Test
    fun `markFailed - records the failure time`() {
        val refund = RefundInfo.request().markFailed()

        assertNotNull(refund.failedAt)
        assertFalse(refund.isConfirmed)
    }

    @Test
    fun `markFailed - throws when already confirmed`() {
        val refund = RefundInfo.request().confirm(testAmount())

        assertThrows<RefundTransitionNotAllowedException> { refund.markFailed() }
    }
}
