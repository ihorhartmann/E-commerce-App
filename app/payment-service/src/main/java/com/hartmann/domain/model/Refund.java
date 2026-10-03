package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Money;

import java.time.Instant;
import java.util.UUID;

public class Refund {
    private final long id;
    private final long paymentId;
    private final UUID idempotencyKey;
    private String externalId;
    private final Money amount;
    private RefundStatus status;

    private final long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private Refund(
            long id,
            long paymentId,
            UUID idempotencyKey,
            String externalId,
            Money amount,
            RefundStatus status,
            long version,
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
        this.version = version;
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

    public long version() {
        return version;
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
        return this.paymentId == paymentId && this.amount.equals(amount);
    }

    /** Whether the refund was already sent to the gateway - only an INITIATED refund still has to be. */
    public boolean isSubmitted() {
        return status != RefundStatus.INITIATED;
    }

    /**
     * The gateway accepted the refund under externalId, reporting gatewayStatus for it - PENDING, or
     * already SUCCEEDED/FAILED when the gateway resolved it synchronously.
     */
    public void submit(String externalId, RefundStatus gatewayStatus) {
        requireStatus("submit", RefundStatus.INITIATED);
        if (externalId == null || externalId.isBlank()) {
            throw new DomainException("Refund " + id + " cannot be submitted without an externalId");
        }
        if (gatewayStatus == RefundStatus.INITIATED) {
            throw new DomainException("Refund " + id + " cannot be submitted as " + gatewayStatus);
        }

        changeState(externalId, gatewayStatus);
    }

    /** The gateway rejected the refund outright, so it will never be paid out. */
    public void fail() {
        requireStatus("fail", RefundStatus.INITIATED);
        changeState(externalId, RefundStatus.FAILED);
    }

    /**
     * Applies a status the gateway reported for this already submitted refund. Reporting the status
     * the refund already has is a no-op, since the gateway may deliver the same event more than once.
     * A terminal status (SUCCEEDED/FAILED) is final: any change to it is rejected, as is a report
     * about a refund that hasn't been submitted yet.
     *
     * @return whether the status changed, false for a repeated report
     */
    public boolean applyGatewayStatus(RefundStatus gatewayStatus) {
        if (gatewayStatus == status) {
            return false;
        }

        requireStatus("apply gateway status " + gatewayStatus + " to", RefundStatus.PENDING);
        if (gatewayStatus == RefundStatus.INITIATED) {
            throw new DomainException("Refund " + id + " cannot go back to " + gatewayStatus);
        }

        changeState(externalId, gatewayStatus);
        return true;
    }

    private void requireStatus(String action, RefundStatus expected) {
        if (status != expected) {
            throw new DomainException(
                    "Cannot " + action + " refund " + id + ": expected status " + expected + " but was " + status);
        }
    }

    private void changeState(String externalId, RefundStatus status) {
        this.externalId = externalId;
        this.status = status;
        this.updatedAt = Instant.now();
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
                + ", externalId='" + externalId + "', amount=" + amount + ", status=" + status + ", version=" + version
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "}";
    }

    public static Refund createNew(long paymentId, UUID idempotencyKey, String externalId, Money amount, RefundStatus status) {
        return new Refund(0L, paymentId, idempotencyKey, externalId, amount, status, 0L, Instant.now(), null);
    }

    public static Refund createExisting(
            long id,
            long paymentId,
            UUID idempotencyKey,
            String externalId,
            Money amount,
            RefundStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (id <= 0) {
            throw new DomainException("Refund id must be positive");
        }
        return new Refund(id, paymentId, idempotencyKey, externalId, amount, status, version, createdAt, updatedAt);
    }
}
