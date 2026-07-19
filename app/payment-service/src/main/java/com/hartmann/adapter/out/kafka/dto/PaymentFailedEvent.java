package com.hartmann.adapter.out.kafka.dto;

public record PaymentFailedEvent(long orderId, long paymentId) {
}
