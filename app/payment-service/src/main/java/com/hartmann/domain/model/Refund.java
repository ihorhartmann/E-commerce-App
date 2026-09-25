package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Money;

import java.time.Instant;
import java.util.UUID;

public class Refund {
    private final long id;
    private final long paymentId;
    private final UUID idempotencyKey;
    private final String externalId;
    private final Money amount;
    private final RefundStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Refund(
            long id,
            long paymentId,
            UUID idempotencyKey,
            String externalId,
            Money amount,
            RefundStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (paymentId <= 0) {
            throw new DomainException("Refund paymentId must be positive");
        }
        if (idempotencyKey == null) {
            throw new DomainException("Refund idempotencyKey must not be null");
        }
        if (amount == null) {
            throw new DomainException("Refund amount must not be null");
        }
        if (status == null) {
            throw new DomainException("Refund status must not be null");
        }
        if (createdAt == null) {
            throw new DomainException("Refund createdAt must not be null");
        }
        // externalId is intentionally not validated here - a freshly drafted refund
        // (Payment.createRefund) doesn't have one yet; Stripe assigns it afterward.

        this.id = id;
        this.paymentId = paymentId;
        this.idempotencyKey = idempotencyKey;
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

    public UUID idempotencyKey() {
        return idempotencyKey;
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

    /**
     * Whether this refund is the one a request for amount against paymentId would have created -
     * false means the request's idempotency key was reused for a different refund.
     */
    public boolean matchesPaymentIdAndAmount(long paymentId, Money amount) {
        return this.paymentId == paymentId
                && this.amount.currency() == amount.currency()
                && this.amount.amount().compareTo(amount.amount()) == 0;
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
        return "Refund{id=" + id + ", paymentId=" + paymentId + ", idempotencyKey=" + idempotencyKey
                + ", externalId='" + externalId + "', amount=" + amount + ", status=" + status
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "}";
    }

    public static Refund createNew(long paymentId, UUID idempotencyKey, String externalId, Money amount, RefundStatus status) {
        return new Refund(0L, paymentId, idempotencyKey, externalId, amount, status, Instant.now(), null);
    }

    public static Refund createExisting(
            long id,
            long paymentId,
            UUID idempotencyKey,
            String externalId,
            Money amount,
            RefundStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (id <= 0) {
            throw new DomainException("Refund id must be positive");
        }
        return new Refund(id, paymentId, idempotencyKey, externalId, amount, status, createdAt, updatedAt);
    }
}
