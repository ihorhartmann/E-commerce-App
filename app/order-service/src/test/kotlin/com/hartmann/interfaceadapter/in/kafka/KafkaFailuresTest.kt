package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.exception.ConflictException
import com.hartmann.entities.exception.ErrorCode
import com.hartmann.entities.exception.TransientException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.MDC

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KafkaFailuresTest {
    private enum class TestErrorCode(override val title: String) : ErrorCode {
        TEST_CONFLICT("Test conflict"),
        TEST_TRANSIENT("Test transient"),
    }

    private class TestConflictException(cause: Throwable? = null, attributes: Map<String, Any?> = emptyMap()) :
        ConflictException(TestErrorCode.TEST_CONFLICT, "conflict", attributes, cause = cause)

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

    @Test
    fun `failureKeyValues - describes the outermost exception of ours with its message, code and the chain's attributes`() {
        val orderId = 7L
        val conflict = TestConflictException(attributes = mapOf("orderId" to orderId))

        val actual = failureKeyValues(RuntimeException("listener failed", conflict))

        val expected = mapOf("exceptionMessage" to conflict.message, "code" to conflict.code.code, "orderId" to orderId)
        assertEquals(expected, actual)
    }

    @Test
    fun `failureKeyValues - is only the message for an exception that isn't ours`() {
        val message = "database unavailable"

        assertEquals(mapOf("exceptionMessage" to message), failureKeyValues(IllegalStateException(message)))
    }

    @Test
    fun `failureKeyValues - leaves out a key the MDC has already`() {
        val conflict = TestConflictException(attributes = mapOf("messageType" to "PaymentCompleted", "orderId" to 8L))

        val actual = MDC.putCloseable("messageType", "PaymentCompleted").use { failureKeyValues(conflict) }

        assertEquals(setOf("exceptionMessage", "code", "orderId"), actual.keys)
    }
}
