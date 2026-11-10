package com.hartmann.application.service.exception;

import com.hartmann.domain.exception.TransientException;

import java.util.Map;

/**
 * The customer asked to cancel an order whose payment the gateway is still processing: it can be neither cancelled
 * nor taken as paid until the gateway decides, which repeating the request shortly is expected to find out.
 */
public class PaymentProcessingException extends TransientException {
    public PaymentProcessingException(long orderId) {
        super(ServiceErrorCode.PAYMENT_PROCESSING, "Payment is being processed", Map.of("orderId", orderId));
    }
}
