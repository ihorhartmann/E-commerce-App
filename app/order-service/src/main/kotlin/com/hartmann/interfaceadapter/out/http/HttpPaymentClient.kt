package com.hartmann.interfaceadapter.out.http

import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
import com.hartmann.usecase.port.out.internal.exception.PaymentGatewayException
import com.hartmann.usecase.port.out.internal.exception.PaymentGatewayUnavailableException
import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.out.http.dto.SubmitPaymentRequest
import com.hartmann.interfaceadapter.out.http.dto.SubmitPaymentResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

/**
 * Transport-level failures (a ResourceAccessException) are deliberately left to propagate as-is:
 * Resilience4jPaymentClient, which wraps this client, needs the underlying cause to tell a request
 * that never reached the payment service (safe to retry) from one that may have.
 */
class HttpPaymentClient(private val restClient: RestClient) : PaymentClient {
    companion object {
        private val LOG = LoggerFactory.getLogger(HttpPaymentClient::class.java)
    }

    override fun submitPayment(orderId: Long, amount: Money): SubmitPaymentResult {
        val response = try {
            restClient.post()
                .uri("/api/v1/payments")
                .header("Idempotency-Key", "payment-order-$orderId")
                .body(SubmitPaymentRequest(orderId, amount.amount, amount.currency.name))
                .retrieve()
                .toEntity<SubmitPaymentResponse>()
        } catch (e: HttpStatusCodeException) {
            when (e.statusCode) {
                HttpStatus.CONFLICT ->
                    throw PaymentGatewayException("Payment gateway rejected the payment for order $orderId")
                HttpStatus.SERVICE_UNAVAILABLE ->
                    throw PaymentGatewayUnavailableException("Payment gateway temporarily unavailable for order $orderId", e)
                else -> {
                    LOG.error(e.message, e)
                    throw e
                }
            }
        }

        check(response.statusCode == HttpStatus.CREATED) {
            "Payment service returned unexpected status ${response.statusCode} for order $orderId"
        }

        val body = response.body ?: error("Payment service returned an empty response for order $orderId")
        return SubmitPaymentResult(body.paymentId, body.clientSecret)
    }
}
