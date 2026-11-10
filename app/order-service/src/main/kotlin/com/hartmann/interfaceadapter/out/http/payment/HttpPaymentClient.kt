package com.hartmann.interfaceadapter.out.http.payment

import com.hartmann.entities.value.Money
import com.hartmann.interfaceadapter.out.http.common.dto.toProblemResponse
import com.hartmann.interfaceadapter.out.http.payment.dto.SubmitPaymentRequest
import com.hartmann.interfaceadapter.out.http.payment.dto.SubmitPaymentResponse
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentAlreadyConfirmedException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentFailureException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentGatewayUnavailableException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentRejectedException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentRequestRejectedException
import com.hartmann.interfaceadapter.out.http.payment.exception.PaymentServiceUnavailableException
import com.hartmann.usecase.port.common.tracing.CorrelationConst
import com.hartmann.usecase.port.out.internal.PaymentClient
import com.hartmann.usecase.port.out.internal.dto.SubmitPaymentResult
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

/** Payment's code for a payment the gateway rejected outright. */
private const val PAYMENT_GATEWAY_REJECTED_CODE = "PAYMENT.PAYMENT_GATEWAY_REJECTED"

/** Payment's code for a gateway that is temporarily unavailable. */
private const val PAYMENT_GATEWAY_UNAVAILABLE_CODE = "PAYMENT.PAYMENT_GATEWAY_UNAVAILABLE"

/** Payment's code for a payment that is already paid, so it can no longer be cancelled. */
private const val PAYMENT_ALREADY_CONFIRMED_CODE = "PAYMENT.PAYMENT_ALREADY_CONFIRMED"

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

    // The key is the order's own: a repeated request for the same order - a retry, or the customer cancelling again -
    // is answered with the outcome of the first one, or, once payment-service forgot it, finds the payment cancelled.
    override fun cancelPayment(correlationId: String, orderId: Long) {
        try {
            restClient.post()
                .uri("/api/v1/payments/{orderId}/cancel", orderId)
                .header("Idempotency-Key", "payment-cancel-order-$orderId")
                .header(CorrelationConst.HTTP_HEADER, correlationId)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpStatusCodeException) {
            throw fromErrorResponse(orderId, e)
        }
    }

    /**
     * Maps an error response by payment's code, never by the status alone: a 409 is a rejected payment as well
     * as a request still in progress, a 503 the gateway being unavailable as well as a concurrent update. Whatever
     * else payment asks to retry is reported as the payment service being unavailable, so the caller is asked to
     * retry too; any other failure is this service's own, not the caller's.
     */
    private fun fromErrorResponse(orderId: Long, e: HttpStatusCodeException): RuntimeException {
        val problem = toProblemResponse(e)
        return when {
            problem?.code == PAYMENT_GATEWAY_REJECTED_CODE -> PaymentRejectedException(orderId, e)
            problem?.code == PAYMENT_GATEWAY_UNAVAILABLE_CODE -> PaymentGatewayUnavailableException(orderId, e)
            problem?.code == PAYMENT_ALREADY_CONFIRMED_CODE -> PaymentAlreadyConfirmedException(orderId, e)
            problem?.retryable == true -> PaymentServiceUnavailableException(orderId, e)
            e.statusCode.is5xxServerError -> PaymentFailureException(orderId, e.statusCode.value(), problem?.code, e)
            else -> PaymentRequestRejectedException(orderId, e.statusCode.value(), problem?.code, e)
        }
    }
}
