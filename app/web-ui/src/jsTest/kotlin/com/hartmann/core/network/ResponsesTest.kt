package com.hartmann.core.network

import com.hartmann.core.network.exception.ApiException
import com.hartmann.core.network.exception.UnauthorizedException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResponsesTest {

    private suspend fun responseOf(status: HttpStatusCode, body: String, contentType: ContentType): HttpResponse {
        val engine = MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, contentType.toString()))
        }
        return HttpClient(engine) { expectSuccess = false }.get("/")
    }

    private suspend fun problemResponse(status: HttpStatusCode, body: String): HttpResponse =
        responseOf(status, body, ContentType("application", "problem+json"))

    @Test
    fun `ensureSuccess - accepts a successful response`() = runTest {
        val response = responseOf(HttpStatusCode.OK, "{}", ContentType.Application.Json)

        response.ensureSuccess()
    }

    @Test
    fun `ensureSuccess - reports an expired session as unauthorized`() = runTest {
        val response = problemResponse(HttpStatusCode.Unauthorized, """{"title":"Unauthorized"}""")

        assertFailsWith<UnauthorizedException> { response.ensureSuccess() }
    }

    @Test
    fun `ensureSuccess - shows a rejected request's detail`() = runTest {
        val detail = "Only 2 left of Laptop"
        val response = problemResponse(
            HttpStatusCode.Conflict,
            """{"title":"Insufficient stock","detail":"$detail","code":"ORDER.INSUFFICIENT_STOCK"}""",
        )

        val actual = assertFailsWith<ApiException> { response.ensureSuccess() }

        assertEquals(HttpStatusCode.Conflict.value, actual.status)
        assertEquals(detail, actual.message)
    }

    @Test
    fun `ensureSuccess - shows a rejected request's title when it has no detail`() = runTest {
        val title = "Order can't be cancelled in its current status"
        val response = problemResponse(HttpStatusCode.Conflict, """{"title":"$title","detail":" "}""")

        val actual = assertFailsWith<ApiException> { response.ensureSuccess() }

        assertEquals(HttpStatusCode.Conflict.value, actual.status)
        assertEquals(title, actual.message)
    }

    @Test
    fun `ensureSuccess - falls back to the status for a body that isn't a problem`() = runTest {
        val response = responseOf(HttpStatusCode.BadRequest, "<html>Bad Request</html>", ContentType.Text.Html)

        val actual = assertFailsWith<ApiException> { response.ensureSuccess() }

        assertEquals(HttpStatusCode.BadRequest.value, actual.status)
        assertEquals("The request was rejected (400).", actual.message)
    }

    @Test
    fun `ensureSuccess - reports a forbidden request without a problem as no access`() = runTest {
        val response = responseOf(HttpStatusCode.Forbidden, "", ContentType.Text.Plain)

        val actual = assertFailsWith<ApiException> { response.ensureSuccess() }

        assertEquals(HttpStatusCode.Forbidden.value, actual.status)
        assertEquals("You don't have access to this.", actual.message)
    }

    @Test
    fun `ensureSuccess - shows the title of a failure to repeat later`() = runTest {
        val title = "Payment gateway is temporarily unavailable"
        val response = problemResponse(HttpStatusCode.ServiceUnavailable, """{"title":"$title","retryable":true}""")

        val actual = assertFailsWith<ApiException> { response.ensureSuccess() }

        assertEquals(HttpStatusCode.ServiceUnavailable.value, actual.status)
        assertEquals(title, actual.message)
    }

    @Test
    fun `ensureSuccess - asks to repeat an unavailable service without a problem`() = runTest {
        val response = responseOf(HttpStatusCode.ServiceUnavailable, "", ContentType.Text.Plain)

        val actual = assertFailsWith<ApiException> { response.ensureSuccess() }

        assertEquals(HttpStatusCode.ServiceUnavailable.value, actual.status)
        assertEquals("The service is busy right now. Try again.", actual.message)
    }

    @Test
    fun `ensureSuccess - hides an internal failure's problem behind a neutral message`() = runTest {
        val response = problemResponse(
            HttpStatusCode.InternalServerError,
            """{"title":"Internal server error","code":"ORDER.INTERNAL_ERROR"}""",
        )

        val actual = assertFailsWith<ApiException> { response.ensureSuccess() }

        assertEquals(HttpStatusCode.InternalServerError.value, actual.status)
        assertEquals("Something went wrong on our side. Try again later.", actual.message)
    }

    @Test
    fun `ensureSuccess - carries the problem's code, which tells one failure from another`() = runTest {
        val code = "ORDER.PAYMENT_ALREADY_CONFIRMED"
        val response = problemResponse(HttpStatusCode.Conflict, """{"title":"Already paid","code":"$code"}""")

        val actual = assertFailsWith<ApiException> { response.ensureSuccess() }

        assertEquals(code, actual.code)
    }
}
