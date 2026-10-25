package com.hartmann.domain.model.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of the rules the payment itself enforces. */
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_TRANSITION_NOT_ALLOWED("Payment can't make this transition in its current status"),
    PAYMENT_NOT_REFUNDABLE("Payment can't be refunded in its current status"),
    REFUND_EXCEEDS_BALANCE("Refund amount exceeds the refundable balance"),
    INVALID_PAYMENT_ORDER_ID("Order id must be positive"),
    INCONSISTENT_PAYMENT("Internal server error"),
    PAYMENT_INVARIANT_VIOLATED("Internal server error");

    private final String title;

    PaymentErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}
