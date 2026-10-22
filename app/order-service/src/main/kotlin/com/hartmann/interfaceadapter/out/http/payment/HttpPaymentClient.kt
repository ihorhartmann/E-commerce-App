package com.hartmann.interfaceadapter.out.http.payment

import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.out.http.payment.dto.SubmitPaymentRequest
import com.hartmann.interfaceadapter.out.http.payment.dto.SubmitPaymentResponse
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentFailureException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentGatewayUnavailableException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentRejectedException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentRequestRejectedException
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
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
    override fun submitPayment(orderId: Long, amount: Money): SubmitPaymentResult {
        val response = try {
            restClient.post()
                .uri("/api/v1/payments")
                .header("Idempotency-Key", "payment-order-$orderId")
                .body(SubmitPaymentRequest(orderId, amount.amount, amount.currency.name))
                .retrieve()
                .toEntity<SubmitPaymentResponse>()
        } catch (e: HttpStatusCodeException) {
            throw fromErrorResponse(orderId, e)
        }

        check(response.statusCode == HttpStatus.CREATED) {
            "Payment service returned unexpected status ${response.statusCode} for order $orderId"
        }

        val body = response.body ?: error("Payment service returned an empty response for order $orderId")
        return SubmitPaymentResult(body.paymentId, body.clientSecret)
    }

    /**
     * Maps an error response by its status alone, unlike HttpInventoryClient: the payment service doesn't send
     * error codes yet. A 409 is the gateway rejecting the payment and a 503 the gateway being unavailable; any
     * other failure is this service's own, not the caller's.
     */
    private fun fromErrorResponse(orderId: Long, e: HttpStatusCodeException): RuntimeException =
        when {
            e.statusCode == HttpStatus.CONFLICT -> PaymentRejectedException(orderId, e)
            e.statusCode == HttpStatus.SERVICE_UNAVAILABLE -> PaymentGatewayUnavailableException(orderId, e)
            e.statusCode.is5xxServerError -> PaymentFailureException(orderId, e.statusCode.value(), e)
            else -> PaymentRequestRejectedException(orderId, e.statusCode.value(), e)
        }
}
