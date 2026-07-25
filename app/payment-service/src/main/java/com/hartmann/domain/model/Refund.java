package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
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
    public Refund {
        if (id < 0) {
            throw new DomainException("Refund id must not be negative");
        }
        if (paymentId <= 0) {
            throw new DomainException("Refund paymentId must be positive");
        }
        if (amount == null) {
            throw new DomainException("Refund amount must not be null");
        }
        if (status == null) {
            throw new DomainException("Refund status must not be null");
        }
        if (createdAt == null || updatedAt == null) {
            throw new DomainException("Refund createdAt/updatedAt must not be null");
        }
        // externalId is intentionally not validated here - a freshly drafted refund
        // (Payment.createRefund) doesn't have one yet; Stripe assigns it afterward.
    }
}
