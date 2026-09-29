package com.hartmann.domain.model;

/** The result of Payment.requestRefund: the payment with its refundable balance reduced, and the refund to submit. */
public record RefundRequest(Payment payment, Refund refund) {
}
