package com.hartmann.application.port.out.http.exception;

import com.hartmann.domain.exception.UnavailableException;

import java.util.Map;

/**
 * The gateway itself is temporarily unable to serve the request (rate limited, unreachable, or having an
 * incident) - unlike PaymentGatewayRejectedException, retrying later is expected to succeed. cause is the
 * gateway's own exception.
 */
public class PaymentGatewayUnavailableException extends UnavailableException {
    private PaymentGatewayUnavailableException(Map<String, ?> attributes, Throwable cause) {
        super(GatewayErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "Payment gateway unavailable", attributes, cause);
    }

    public static PaymentGatewayUnavailableException ofPayment(long orderId, Throwable cause) {
        return new PaymentGatewayUnavailableException(Map.of("orderId", orderId), cause);
    }

    public static PaymentGatewayUnavailableException ofRefund(String paymentExternalId, long refundId, Throwable cause) {
        return new PaymentGatewayUnavailableException(
                Map.of("paymentExternalId", paymentExternalId, "refundId", refundId), cause);
    }
}
