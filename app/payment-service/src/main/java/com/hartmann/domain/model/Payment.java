package com.hartmann.domain.model;

import com.hartmann.domain.value.Money;

import java.time.Instant;

public record Payment(
        long id,
        long orderId,
        Money amount,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
