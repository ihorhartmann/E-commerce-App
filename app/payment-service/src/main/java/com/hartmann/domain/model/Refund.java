package com.hartmann.domain.model;

import com.hartmann.domain.value.Money;

import java.time.Instant;

public record Refund(
        long id,
        long paymentId,
        String externalId,
        Money amount,
        RefundStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
