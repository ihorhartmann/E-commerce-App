package com.hartmann.application.service.exception;

import com.hartmann.domain.exception.ConflictException;

import java.util.Map;

/**
 * The customer asked to cancel an order whose payment is already confirmed - possibly only just, the gateway
 * telling so instead of cancelling it. Its order is no longer cancelled before payment, but the way any paid order is.
 */
public class PaymentAlreadyConfirmedException extends ConflictException {
    public PaymentAlreadyConfirmedException(long orderId) {
        super(ServiceErrorCode.PAYMENT_ALREADY_CONFIRMED, "Payment already confirmed", Map.of("orderId", orderId));
    }
}
