package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.ConflictException;
import com.hartmann.domain.model.PaymentStatus;

import java.util.Map;

/** Only a confirmed payment can be refunded. */
public class PaymentNotRefundableException extends ConflictException {
    public PaymentNotRefundableException(long paymentId, PaymentStatus status) {
        super(PaymentErrorCode.PAYMENT_NOT_REFUNDABLE, "Payment can't be refunded",
                Map.of("paymentId", paymentId, "status", status));
    }
}
