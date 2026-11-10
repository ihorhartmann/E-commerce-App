package com.hartmann.interfaceadapter.out.http.payment

import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentAlreadyConfirmedException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentFailureException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentGatewayUnavailableException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentRejectedException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentRequestRejectedException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentServiceUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.math.BigDecimal

private const val PAYMENTS_URL = "http://payment/api/v1/payments"

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class HttpPaymentClientTest {
    private val restClientBuilder = RestClient.builder().baseUrl("http://payment")
    private val server = MockRestServiceServer.bindTo(restClientBuilder).build()
    private val client = HttpPaymentClient(restClientBuilder.build())

    private fun testAmount() = Money(BigDecimal("10.00"), Currency.EUR)

    private fun respond(status: HttpStatus, body: String, contentType: MediaType = MediaType.APPLICATION_PROBLEM_JSON) {
        server.expect(requestTo(PAYMENTS_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(status).contentType(contentType).body(body))
    }

    @Test
    fun `submitPayment - returns the payment id and client secret`() {
        val orderId = 1L
        val paymentId = 5L
        val clientSecret = "pi_secret"
        respond(
            HttpStatus.CREATED,
            """{"paymentId":$paymentId,"orderId":$orderId,"externalId":"pi_1","clientSecret":"$clientSecret",""" +
                """"status":"PENDING"}""",
            MediaType.APPLICATION_JSON,
        )

        val actual = client.submitPayment(orderId, testAmount())

        assertEquals(paymentId, actual.paymentId)
        assertEquals(clientSecret, actual.clientSecret)
        server.verify()
    }

    @Test
    fun `submitPayment - throws PaymentRejectedException when the payment gateway rejects the payment`() {
        val orderId = 1L
        respond(HttpStatus.CONFLICT, """{"status":409,"code":"PAYMENT.PAYMENT_GATEWAY_REJECTED","retryable":false}""")

        val exception = assertThrows<PaymentRejectedException> { client.submitPayment(orderId, testAmount()) }

        assertEquals(orderId, exception.orderId)
        server.verify()
    }

    @Test
    fun `submitPayment - throws PaymentGatewayUnavailableException when the payment gateway is unavailable`() {
        respond(
            HttpStatus.SERVICE_UNAVAILABLE,
            """{"status":503,"code":"PAYMENT.PAYMENT_GATEWAY_UNAVAILABLE","retryable":true}""",
        )

        assertThrows<PaymentGatewayUnavailableException> { client.submitPayment(1L, testAmount()) }

        server.verify()
    }

    @Test
    fun `submitPayment - throws PaymentServiceUnavailableException when payment asks to retry, even with a 409`() {
        respond(HttpStatus.CONFLICT, """{"status":409,"code":"PAYMENT.REQUEST_IN_PROGRESS","retryable":true}""")

        assertThrows<PaymentServiceUnavailableException> { client.submitPayment(1L, testAmount()) }

        server.verify()
    }

    @Test
    fun `submitPayment - throws PaymentFailureException with payment's code for a 5xx it doesn't ask to retry`() {
        val paymentCode = "PAYMENT.DATABASE_FAILURE"
        respond(HttpStatus.INTERNAL_SERVER_ERROR, """{"status":500,"code":"$paymentCode","retryable":false}""")

        val exception = assertThrows<PaymentFailureException> { client.submitPayment(1L, testAmount()) }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), exception.status)
        assertEquals(paymentCode, exception.paymentCode)
        server.verify()
    }

    @Test
    fun `submitPayment - throws PaymentFailureException for a 5xx whose body is no ProblemDetail`() {
        respond(HttpStatus.BAD_GATEWAY, "<html>Bad Gateway</html>", MediaType.TEXT_HTML)

        val exception = assertThrows<PaymentFailureException> { client.submitPayment(1L, testAmount()) }

        assertEquals(null, exception.paymentCode)
        // the body couldn't be read as a ProblemDetail: that failure stays on the response exception
        assertEquals(1, exception.cause!!.suppressed.size)
        server.verify()
    }

    @Test
    fun `submitPayment - throws PaymentRequestRejectedException for any other 4xx`() {
        val paymentCode = "PAYMENT.UNSUPPORTED_CURRENCY"
        respond(HttpStatus.BAD_REQUEST, """{"status":400,"code":"$paymentCode","retryable":false}""")

        val exception = assertThrows<PaymentRequestRejectedException> { client.submitPayment(1L, testAmount()) }

        assertEquals(HttpStatus.BAD_REQUEST.value(), exception.status)
        assertEquals(paymentCode, exception.paymentCode)
        server.verify()
    }

    private fun respondToCancel(orderId: Long, status: HttpStatus, body: String) {
        server.expect(requestTo("$PAYMENTS_URL/$orderId/cancel"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body))
    }

    @Test
    fun `cancelPayment - posts to the order's cancel endpoint under a key of the order, with the saga's correlation id`() {
        val orderId = 1L
        val correlationId = "1"
        server.expect(requestTo("$PAYMENTS_URL/$orderId/cancel"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Idempotency-Key", "payment-cancel-order-$orderId"))
            .andExpect(header("X-Correlation-Id", correlationId))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"id":5,"orderId":$orderId,"status":"CANCELLED"}"""),
            )

        client.cancelPayment(correlationId, orderId)

        server.verify()
    }

    @Test
    fun `cancelPayment - throws PaymentAlreadyConfirmedException when the payment is already paid`() {
        val orderId = 1L
        respondToCancel(
            orderId,
            HttpStatus.CONFLICT,
            """{"status":409,"code":"PAYMENT.PAYMENT_ALREADY_CONFIRMED","retryable":false}""",
        )

        val exception = assertThrows<PaymentAlreadyConfirmedException> { client.cancelPayment("1", orderId) }

        assertEquals(orderId, exception.orderId)
        server.verify()
    }

    @Test
    fun `cancelPayment - throws PaymentServiceUnavailableException when payment asks to retry`() {
        val orderId = 1L
        respondToCancel(
            orderId,
            HttpStatus.SERVICE_UNAVAILABLE,
            """{"status":503,"code":"PAYMENT.PAYMENT_PROCESSING","retryable":true}""",
        )

        assertThrows<PaymentServiceUnavailableException> { client.cancelPayment("1", orderId) }

        server.verify()
    }
}
