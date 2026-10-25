package com.hartmann.application.port.out.repository.exception;

import com.hartmann.domain.exception.NotFoundException;

import java.util.Map;

/** One class for every way a payment is looked up; the attribute says which one this lookup used. */
public class PaymentNotFoundException extends NotFoundException {
    private PaymentNotFoundException(Map<String, ?> attributes) {
        super(RepositoryErrorCode.PAYMENT_NOT_FOUND, "Payment not found", attributes);
    }

    public static PaymentNotFoundException withId(long paymentId) {
        return new PaymentNotFoundException(Map.of("paymentId", paymentId));
    }

    public static PaymentNotFoundException withOrderId(long orderId) {
        return new PaymentNotFoundException(Map.of("orderId", orderId));
    }

    public static PaymentNotFoundException withExternalId(String externalId) {
        return new PaymentNotFoundException(Map.of("externalId", externalId));
    }
}
