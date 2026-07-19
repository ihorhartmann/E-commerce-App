package com.hartmann.adapter.in.kafka.dto;

import java.math.BigDecimal;

public record RefundRequestedMessage(long orderId, BigDecimal amount, String currency) {
}
