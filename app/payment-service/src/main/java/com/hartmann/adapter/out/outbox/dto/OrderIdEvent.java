package com.hartmann.adapter.out.outbox.dto;

// Shared shape for the payment events that carry nothing beyond which order they're about
// (PaymentCompleted, PaymentFailed, PaymentExpired, PaymentCancelled, RefundFailed)
public record OrderIdEvent(long orderId) {
}
