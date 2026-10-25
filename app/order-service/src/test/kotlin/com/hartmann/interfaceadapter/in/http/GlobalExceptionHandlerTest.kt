package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.entities.exception.ConflictException
import com.hartmann.entities.exception.ErrorCode
import com.hartmann.entities.exception.UnavailableException
import com.hartmann.interfaceadapter.`in`.http.exception.HttpErrorCode
import com.hartmann.interfaceadapter.`in`.http.exception.IdempotencyKeyReusedException
import com.hartmann.interfaceadapter.`in`.http.exception.RequestInProgressException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.context.request.ServletWebRequest
import java.net.URI

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GlobalExceptionHandlerTest {
    private enum class TestErrorCode(override val title: String) : ErrorCode {
        TEST_CONFLICT("Test conflict"),
        TEST_UNAVAILABLE("Test unavailable"),
    }

    private class TestConflictException(paymentId: Long, externalMessage: String?) : ConflictException(
        TestErrorCode.TEST_CONFLICT,
        "Refund exceeds the refundable balance",
        mapOf("paymentId" to paymentId),
        externalMessage = externalMessage,
    )

    private class TestUnavailableException :
        UnavailableException(TestErrorCode.TEST_UNAVAILABLE, "Inventory answered 503 for order 7")

    private val handler = GlobalExceptionHandler()
    private val request = MockHttpServletRequest("POST", "/api/v1/orders")

    @Test
    fun `service - renders a conflict as 409 with its external message and never its internal one`() {
        val detail = "The refund amount exceeds the refundable balance."

        val response = handler.service(TestConflictException(5, detail), request)

        val problem = response.body!!
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(HttpStatus.CONFLICT.value(), problem.status)
        assertEquals(URI.create("urn:ecommerce:error:ORDER.TEST_CONFLICT"), problem.type)
        assertEquals(TestErrorCode.TEST_CONFLICT.title, problem.title)
        assertEquals(detail, problem.detail)
        assertEquals("ORDER.TEST_CONFLICT", problem.properties!![GlobalExceptionHandler.CODE_PROPERTY])
        assertEquals(false, problem.properties!![GlobalExceptionHandler.RETRYABLE_PROPERTY])
        assertNull(response.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }

    @Test
    fun `service - renders an unavailable dependency as a retryable 503 with Retry-After`() {
        val response = handler.service(TestUnavailableException(), request)

        val problem = response.body!!
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals(TestErrorCode.TEST_UNAVAILABLE.code, problem.properties!![GlobalExceptionHandler.CODE_PROPERTY])
        assertEquals(true, problem.properties!![GlobalExceptionHandler.RETRYABLE_PROPERTY])
        assertNull(problem.detail)
        assertEquals(
            GlobalExceptionHandler.RETRY_AFTER_SECONDS.toString(),
            response.headers.getFirst(HttpHeaders.RETRY_AFTER),
        )
    }

    @Test
    fun `service - renders a reused Idempotency-Key as 422`() {
        val response = handler.service(IdempotencyKeyReusedException("key-1"), request)

        val problem = response.body!!
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.statusCode)
        assertEquals(HttpErrorCode.IDEMPOTENCY_KEY_REUSED.code, problem.properties!![GlobalExceptionHandler.CODE_PROPERTY])
        assertEquals(false, problem.properties!![GlobalExceptionHandler.RETRYABLE_PROPERTY])
    }

    @Test
    fun `service - renders a request in progress as a retryable 409 with Retry-After`() {
        val response = handler.service(RequestInProgressException("key-1"), request)

        val problem = response.body!!
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(HttpErrorCode.REQUEST_IN_PROGRESS.code, problem.properties!![GlobalExceptionHandler.CODE_PROPERTY])
        assertEquals(true, problem.properties!![GlobalExceptionHandler.RETRYABLE_PROPERTY])
        assertEquals(
            GlobalExceptionHandler.RETRY_AFTER_SECONDS.toString(),
            response.headers.getFirst(HttpHeaders.RETRY_AFTER),
        )
    }

    @Test
    fun `unexpected - renders any other exception as 500 without its message`() {
        val response = handler.unexpected(IllegalStateException("pq: password authentication failed"), request)

        val problem = response.body!!
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals(HttpErrorCode.INTERNAL_ERROR.title, problem.title)
        assertEquals(HttpErrorCode.INTERNAL_ERROR.code, problem.properties!![GlobalExceptionHandler.CODE_PROPERTY])
        assertEquals(false, problem.properties!![GlobalExceptionHandler.RETRYABLE_PROPERTY])
        assertNull(problem.detail)
    }

    @Test
    fun `handleExceptionInternal - gives the code to a ProblemDetail Spring builds itself`() {
        val response = handler.handleException(HttpRequestMethodNotSupportedException("DELETE"), ServletWebRequest(request))

        val problem = response!!.body as ProblemDetail
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.statusCode)
        assertEquals(URI.create("urn:ecommerce:error:${HttpErrorCode.METHOD_NOT_ALLOWED.code}"), problem.type)
        assertEquals(HttpErrorCode.METHOD_NOT_ALLOWED.title, problem.title)
        assertEquals(HttpErrorCode.METHOD_NOT_ALLOWED.code, problem.properties!![GlobalExceptionHandler.CODE_PROPERTY])
        assertEquals(false, problem.properties!![GlobalExceptionHandler.RETRYABLE_PROPERTY])
    }

    @Test
    fun `unexpected - renders an exception wrapping one of ours as the one it wraps`() {
        val detail = "The refund amount exceeds the refundable balance."

        val response = handler.unexpected(RuntimeException("transaction rolled back", TestConflictException(5, detail)), request)

        val problem = response.body!!
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(TestErrorCode.TEST_CONFLICT.code, problem.properties!![GlobalExceptionHandler.CODE_PROPERTY])
        assertEquals(detail, problem.detail)
    }
}
