package com.hartmann.adapter.out.kafka.dto;

import java.math.BigDecimal;

public record RefundCompletedEvent(long orderId, long paymentId, BigDecimal amount, String currency) {
}
