package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Money;

import java.time.Instant;
import java.util.List;

public class Payment {
    private final long id;
    private final long orderId;
    private final String externalId;
    private final Money amount;
    private final PaymentStatus status;
    private final List<Refund> refunds;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Payment(
            long id,
            long orderId,
            String externalId,
            Money amount,
            PaymentStatus status,
            List<Refund> refunds,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (orderId <= 0) {
            throw new DomainException("Payment orderId must be positive");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new DomainException("Payment externalId must not be blank");
        }
        if (amount == null) {
            throw new DomainException("Payment amount must not be null");
        }
        if (status == null) {
            throw new DomainException("Payment status must not be null");
        }
        if (refunds == null) {
            throw new DomainException("Payment refunds must not be null");
        }
        if (createdAt == null) {
            throw new DomainException("Payment createdAt must not be null");
        }

        this.id = id;
        this.orderId = orderId;
        this.externalId = externalId;
        this.amount = amount;
        this.status = status;
        this.refunds = refunds;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long id() {
        return id;
    }

    public long orderId() {
        return orderId;
    }

    public String externalId() {
        return externalId;
    }

    public Money amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public List<Refund> refunds() {
        return refunds;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Refund createRefund(Money amount) {
        if (status != PaymentStatus.COMPLETED) {
            throw new DomainException("Payment " + id + " is not completed and cannot be refunded");
        }
        amount.requireSameCurrency(this.amount);

        var blockedRefunds = refunds.stream()
                .filter(refund -> refund.status() != RefundStatus.FAILED)
                .map(Refund::amount)
                .reduce(Money.zero(this.amount.currency()), Money::add);
        var refundableBalance = this.amount.subtract(blockedRefunds);

        if (amount.isGreaterThan(refundableBalance)) {
            throw new DomainException(
                    "Refund amount " + amount.amount() + " exceeds refundable balance " + refundableBalance.amount()
                            + " for payment " + id);
        }

        return Refund.createNew(id, null, amount, RefundStatus.PENDING);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Payment other)) {
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
        return "Payment{id=" + id + ", orderId=" + orderId + ", externalId='" + externalId + "', amount=" + amount
                + ", status=" + status + ", refunds=" + refunds + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + "}";
    }

    public static Payment createNew(long orderId, String externalId, Money amount) {
        return new Payment(0L, orderId, externalId, amount, PaymentStatus.PENDING, List.of(), Instant.now(), null);
    }

    public static Payment createExisting(
            long id,
            long orderId,
            String externalId,
            Money amount,
            PaymentStatus status,
            List<Refund> refunds,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (id <= 0) {
            throw new DomainException("Payment id must be positive");
        }
        return new Payment(id, orderId, externalId, amount, status, refunds, createdAt, updatedAt);
    }
}
