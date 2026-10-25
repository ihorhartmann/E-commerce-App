package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.exception.ConflictException
import com.hartmann.entities.exception.ErrorCode
import com.hartmann.entities.exception.TransientException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KafkaFailuresTest {
    private enum class TestErrorCode(override val title: String) : ErrorCode {
        TEST_CONFLICT("Test conflict"),
        TEST_TRANSIENT("Test transient"),
    }

    private class TestConflictException(cause: Throwable? = null) :
        ConflictException(TestErrorCode.TEST_CONFLICT, "conflict", cause = cause)

    private class TestTransientException(cause: Throwable? = null) :
        TransientException(TestErrorCode.TEST_TRANSIENT, "transient", cause = cause)

    @Test
    fun `isPermanentFailure - is true for a permanent exception`() {
        assertEquals(true, isPermanentFailure(TestConflictException()))
    }

    @Test
    fun `isPermanentFailure - is true for a permanent exception wrapped in one that isn't ours`() {
        assertEquals(true, isPermanentFailure(RuntimeException("listener failed", TestConflictException())))
    }

    @Test
    fun `isPermanentFailure - is decided by the outermost exception of ours`() {
        assertEquals(false, isPermanentFailure(TestTransientException(TestConflictException())))
    }

    @Test
    fun `isPermanentFailure - is false for an exception that isn't ours`() {
        assertEquals(false, isPermanentFailure(IllegalStateException("database unavailable")))
    }
}
