package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Payment {
    // How long a payment attempt stays open (INITIATED/PENDING/ATTEMPT_DECLINED) before the
    // expiration sweep marks it EXPIRED. Fixed at creation - not extended by ATTEMPT_DECLINED retries.
    private static final Duration EXPIRATION = Duration.ofMinutes(15);

    // Statuses a payment can be in without ever having gotten a PaymentIntent out of Stripe:
    // INITIATED (persisted before that gateway call, for a durable audit trail), FAILED (the
    // gateway call itself failed synchronously - see PaymentServiceImpl.submitToGateway), and
    // EXPIRED reached directly from INITIATED (the sweep gave up before the gateway call ever
    // completed). Every other status is only reachable once externalId is already assigned.
    private static final Set<PaymentStatus> STATUSES_ALLOWING_NO_EXTERNAL_ID =
            Set.of(PaymentStatus.INITIATED, PaymentStatus.FAILED, PaymentStatus.EXPIRED);

    private final long id;
    private final long orderId;
    private final String externalId;
    private final Money amount;
    private final PaymentStatus status;
    private final List<Refund> refunds;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant expiresAt;

    private Payment(
            long id,
            long orderId,
            String externalId,
            Money amount,
            PaymentStatus status,
            List<Refund> refunds,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {
        if (orderId <= 0) {
            throw new DomainException("Payment orderId must be positive");
        }
        if (status == null) {
            throw new DomainException("Payment status must not be null");
        }
        if (!STATUSES_ALLOWING_NO_EXTERNAL_ID.contains(status) && (externalId == null || externalId.isBlank())) {
            throw new DomainException("Payment externalId must not be blank once submitted");
        }
        if (amount == null) {
            throw new DomainException("Payment amount must not be null");
        }
        if (refunds == null) {
            throw new DomainException("Payment refunds must not be null");
        }
        if (createdAt == null) {
            throw new DomainException("Payment createdAt must not be null");
        }
        if (expiresAt == null) {
            throw new DomainException("Payment expiresAt must not be null");
        }

        this.id = id;
        this.orderId = orderId;
        this.externalId = externalId;
        this.amount = amount;
        this.status = status;
        this.refunds = refunds;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.expiresAt = expiresAt;
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

    public Instant expiresAt() {
        return expiresAt;
    }

    public Refund createRefund(Money amount, UUID idempotencyKey) {
        if (status != PaymentStatus.CONFIRMED) {
            throw new DomainException("Payment " + id + " is not confirmed and cannot be refunded");
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

        return Refund.createNew(id, idempotencyKey, null, amount, RefundStatus.INITIATED);
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
                + ", updatedAt=" + updatedAt + ", expiresAt=" + expiresAt + "}";
    }

    /** A brand-new payment attempt, persisted before the PaymentIntent is created on Stripe. */
    public static Payment createNew(long orderId, Money amount) {
        var now = Instant.now();
        return new Payment(0L, orderId, null, amount, PaymentStatus.INITIATED, List.of(), now, null, now.plus(EXPIRATION));
    }

    public static Payment createExisting(
            long id,
            long orderId,
            String externalId,
            Money amount,
            PaymentStatus status,
            List<Refund> refunds,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {
        if (id <= 0) {
            throw new DomainException("Payment id must be positive");
        }
        return new Payment(id, orderId, externalId, amount, status, refunds, createdAt, updatedAt, expiresAt);
    }
}
