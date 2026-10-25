package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.ConflictException;
import com.hartmann.domain.model.PaymentStatus;

import java.util.Map;

/** The payment was asked to action - e.g. confirm - in a status that doesn't allow it. */
public class PaymentTransitionNotAllowedException extends ConflictException {
    public PaymentTransitionNotAllowedException(long paymentId, String action, PaymentStatus status) {
        super(PaymentErrorCode.PAYMENT_TRANSITION_NOT_ALLOWED, "Payment transition not allowed",
                Map.of("paymentId", paymentId, "action", action, "status", status));
    }
}
