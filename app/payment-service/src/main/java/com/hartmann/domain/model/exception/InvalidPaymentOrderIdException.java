package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/** A payment was requested for an order id that can't be one - the caller's request is wrong. */
public class InvalidPaymentOrderIdException extends InvalidException {
    public InvalidPaymentOrderIdException(long orderId) {
        super(PaymentErrorCode.INVALID_PAYMENT_ORDER_ID, "Invalid payment order id", Map.of("orderId", orderId));
    }
}
