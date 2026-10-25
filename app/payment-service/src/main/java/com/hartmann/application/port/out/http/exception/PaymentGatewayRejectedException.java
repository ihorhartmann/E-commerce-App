package com.hartmann.application.port.out.http.exception;

import com.hartmann.domain.exception.ConflictException;

import java.util.Map;

/**
 * The gateway rejected the request outright - a declined card, an amount it doesn't accept - so repeating it
 * can't help. cause is the gateway's own exception.
 */
public class PaymentGatewayRejectedException extends ConflictException {
    private PaymentGatewayRejectedException(Map<String, ?> attributes, Throwable cause) {
        super(GatewayErrorCode.PAYMENT_GATEWAY_REJECTED, "Payment gateway rejected the request", attributes, cause);
    }

    public static PaymentGatewayRejectedException ofPayment(long orderId, Throwable cause) {
        return new PaymentGatewayRejectedException(Map.of("orderId", orderId), cause);
    }

    public static PaymentGatewayRejectedException ofRefund(String paymentExternalId, long refundId, Throwable cause) {
        return new PaymentGatewayRejectedException(
                Map.of("paymentExternalId", paymentExternalId, "refundId", refundId), cause);
    }
}
