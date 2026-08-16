package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Money;

import java.time.Instant;

public class Refund {
    private final long id;
    private final long paymentId;
    private final String externalId;
    private final Money amount;
    private final RefundStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Refund(
            long id,
            long paymentId,
            String externalId,
            Money amount,
            RefundStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
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

        this.id = id;
        this.paymentId = paymentId;
        this.externalId = externalId;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long id() {
        return id;
    }

    public long paymentId() {
        return paymentId;
    }

    public String externalId() {
        return externalId;
    }

    public Money amount() {
        return amount;
    }

    public RefundStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Refund other)) {
            return false;
        }
        return id != 0 && id == other.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "Refund{id=" + id + ", paymentId=" + paymentId + ", externalId='" + externalId + "', amount=" + amount
                + ", status=" + status + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "}";
    }

    public static Refund createNew(long paymentId, String externalId, Money amount, RefundStatus status) {
        var now = Instant.now();
        return new Refund(0L, paymentId, externalId, amount, status, now, now);
    }

    public static Refund createExisting(
            long id,
            long paymentId,
            String externalId,
            Money amount,
            RefundStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (id <= 0) {
            throw new DomainException("Refund id must be positive");
        }
        return new Refund(id, paymentId, externalId, amount, status, createdAt, updatedAt);
    }
}
