package com.hartmann.adapter.out.http.exception;

import com.hartmann.domain.exception.PaymentServiceException;

import java.util.Map;

/**
 * Stripe refused this service's API key - missing, revoked, or without the permission the call needs. An internal
 * failure, not a rejection of the request: retrying is pointless until the key is fixed, but a Kafka message is
 * still retried (and then dead-lettered for a redrive) rather than dropped as permanent. Not part of the gateway
 * port: no caller tells it apart from any other internal failure. cause is Stripe's own exception.
 */
public class PaymentGatewayAuthenticationException extends PaymentServiceException {
    private PaymentGatewayAuthenticationException(Map<String, ?> attributes, Throwable cause) {
        super(StripeGatewayErrorCode.PAYMENT_GATEWAY_AUTHENTICATION_FAILED, "Payment gateway refused the API key", null,
                attributes, cause);
    }

    public static PaymentGatewayAuthenticationException ofPayment(long orderId, Throwable cause) {
        return new PaymentGatewayAuthenticationException(Map.of("orderId", orderId), cause);
    }

    public static PaymentGatewayAuthenticationException ofRefund(String paymentExternalId, long refundId, Throwable cause) {
        return new PaymentGatewayAuthenticationException(
                Map.of("paymentExternalId", paymentExternalId, "refundId", refundId), cause);
    }
}
