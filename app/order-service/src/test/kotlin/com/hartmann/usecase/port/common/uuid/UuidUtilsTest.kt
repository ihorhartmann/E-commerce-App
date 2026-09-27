package com.hartmann.usecase.port.common.uuid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class UuidUtilsTest {

    @Test
    fun `generateV7 - returns a version 7 uuid stamped with the current time`() {
        val before = System.currentTimeMillis()

        val uuid = UuidUtils.generateV7()

        val after = System.currentTimeMillis()
        val actualEpochMillis = uuid.mostSignificantBits ushr 16
        assertEquals(7, uuid.version())
        assertTrue(actualEpochMillis in before..after)
    }
}
