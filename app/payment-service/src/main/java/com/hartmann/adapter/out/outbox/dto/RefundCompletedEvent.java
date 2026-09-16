package com.hartmann.adapter.out.outbox.dto;

import com.hartmann.domain.value.Money;

import java.math.BigDecimal;

public record RefundCompletedEvent(long orderId, BigDecimal amount, String currency) {
    public static RefundCompletedEvent from(long orderId, Money amount) {
        return new RefundCompletedEvent(orderId, amount.amount(), amount.currency().name());
    }
}
