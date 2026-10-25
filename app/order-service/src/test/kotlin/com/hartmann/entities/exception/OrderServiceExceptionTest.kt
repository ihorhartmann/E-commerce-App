package com.hartmann.entities.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderServiceExceptionTest {
    private enum class TestErrorCode(override val title: String) : ErrorCode {
        TEST_ERROR("Test error"),
    }

    private class TestInvalidException : InvalidException(TestErrorCode.TEST_ERROR, "invalid")
    private class TestNotFoundException : NotFoundException(TestErrorCode.TEST_ERROR, "not found")
    private class TestConflictException : ConflictException(TestErrorCode.TEST_ERROR, "conflict")
    private class TestTransientException : TransientException(TestErrorCode.TEST_ERROR, "transient")
    private class TestUnavailableException : UnavailableException(TestErrorCode.TEST_ERROR, "unavailable")
    private class TestInternalException : OrderServiceException(TestErrorCode.TEST_ERROR, "internal")

    @Test
    fun `permanent - is true for an invalid request, a missing entity and a business conflict`() {
        assertEquals(true, TestInvalidException().permanent)
        assertEquals(true, TestNotFoundException().permanent)
        assertEquals(true, TestConflictException().permanent)
    }

    @Test
    fun `permanent - is false for a transient failure, an unavailable dependency and an internal failure`() {
        assertEquals(false, TestTransientException().permanent)
        assertEquals(false, TestUnavailableException().permanent)
        assertEquals(false, TestInternalException().permanent)
    }

    @Test
    fun `retryable - is true for a transient failure and an unavailable dependency`() {
        assertEquals(true, TestTransientException().retryable)
        assertEquals(true, TestUnavailableException().retryable)
    }

    @Test
    fun `retryable - is false for a permanent failure and an internal failure`() {
        assertEquals(false, TestInvalidException().retryable)
        assertEquals(false, TestNotFoundException().retryable)
        assertEquals(false, TestConflictException().retryable)
        assertEquals(false, TestInternalException().retryable)
    }
}
