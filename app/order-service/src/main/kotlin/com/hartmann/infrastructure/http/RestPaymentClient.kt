package com.hartmann.infrastructure.http

import com.hartmann.application.port.out.http.PaymentClient
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult
import com.hartmann.domain.value.Money
import com.hartmann.infrastructure.http.dto.SubmitPaymentRequest
import com.hartmann.infrastructure.http.dto.SubmitPaymentResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

@Component
class RestPaymentClient(
    @Value($$"${payment-service.url}") baseUrl: String,
    loggingInterceptor: LoggingClientHttpRequestInterceptor,
) : PaymentClient {
    private val restClient = RestClient.builder().baseUrl(baseUrl).requestInterceptor(loggingInterceptor).build()

    override fun submitPayment(orderId: Long, amount: Money): SubmitPaymentResult {
        val response = restClient.post()
            .uri("/api/v1/payments")
            .body(SubmitPaymentRequest(orderId, amount.amount, amount.currency.name))
            .retrieve()
            .toEntity<SubmitPaymentResponse>()

        check(response.statusCode == HttpStatus.CREATED) {
            "Payment service returned unexpected status ${response.statusCode} for order $orderId"
        }

        val body = response.body ?: error("Payment service returned an empty response for order $orderId")
        return SubmitPaymentResult(body.paymentId, body.clientSecret)
    }
}
