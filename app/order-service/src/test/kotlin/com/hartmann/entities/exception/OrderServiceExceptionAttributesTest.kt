package com.hartmann.entities.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderServiceExceptionAttributesTest {
    private enum class TestErrorCode(override val title: String) : ErrorCode {
        TEST_NOT_FOUND("Test not found"),
        TEST_UNAVAILABLE("Test unavailable"),
    }

    private class TestNotFoundException(productId: Long, orderId: Long) : NotFoundException(
        TestErrorCode.TEST_NOT_FOUND,
        "Product not found",
        mapOf("productId" to productId, "orderId" to orderId),
    )

    private class TestUnavailableException(orderId: Long, cause: Throwable) : UnavailableException(
        TestErrorCode.TEST_UNAVAILABLE,
        "Reservation failed",
        mapOf("orderId" to orderId),
        cause = cause,
    )

    @Test
    fun `attributesOf - returns the attributes of a single exception`() {
        val productId = 7L
        val orderId = 3L

        val actual = attributesOf(TestNotFoundException(productId, orderId))

        assertEquals(mapOf("productId" to productId, "orderId" to orderId), actual)
    }

    @Test
    fun `attributesOf - merges the attributes of the whole cause chain with the outer value winning`() {
        val productId = 7L
        val outerOrderId = 4L
        val exception =
            TestUnavailableException(outerOrderId, IllegalStateException(TestNotFoundException(productId, 3)))

        val actual = attributesOf(exception)

        assertEquals(mapOf("productId" to productId, "orderId" to outerOrderId), actual)
    }

    @Test
    fun `attributesOf - returns no attributes for an exception that isn't a ServiceException`() {
        val actual = attributesOf(IllegalStateException("connection refused"))

        assertEquals(emptyMap<String, Any?>(), actual)
    }
}
