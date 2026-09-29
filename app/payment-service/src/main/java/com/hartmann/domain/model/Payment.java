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
    private final Money refundedAmount;

    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant expiresAt;

    private Payment(
            long id,
            long orderId,
            String externalId,
            Money amount,
            PaymentStatus status,
            Money refundedAmount,
            long version,
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
        if (refundedAmount == null) {
            throw new DomainException("Payment refundedAmount must not be null");
        }
        amount.requireSameCurrency(refundedAmount);
        if (refundedAmount.isGreaterThan(amount)) {
            throw new DomainException("Payment refundedAmount must not exceed its amount");
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
        this.refundedAmount = refundedAmount;
        this.version = version;
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

    public Money refundedAmount() {
        return refundedAmount;
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

    public Instant expiresAt() {
        return expiresAt;
    }

    public RefundRequest requestRefund(Money amount, UUID idempotencyKey) {
        if (status != PaymentStatus.CONFIRMED) {
            throw new DomainException("Payment " + id + " is not confirmed and cannot be refunded");
        }
        amount.requireSameCurrency(this.amount);

        var refundableBalance = this.amount.subtract(refundedAmount);
        if (amount.isGreaterThan(refundableBalance)) {
            throw new DomainException(
                    "Refund amount " + amount.amount() + " exceeds refundable balance " + refundableBalance.amount()
                            + " for payment " + id);
        }

        return new RefundRequest(
                withRefundedAmount(refundedAmount.add(amount)),
                Refund.createNew(id, idempotencyKey, null, amount, RefundStatus.INITIATED));
    }

    public Payment releaseRefund(Refund failedRefund) {
        if (failedRefund.paymentId() != id || failedRefund.status() != RefundStatus.FAILED) {
            throw new DomainException("Refund " + failedRefund.id() + " is not a failed refund of payment " + id);
        }

        return withRefundedAmount(refundedAmount.subtract(failedRefund.amount()));
    }

    /**
     * Whether the payment ever got a PaymentIntent out of the gateway, i.e. the customer could start paying it.
     */
    public boolean wasSubmitted() {
        return externalId != null;
    }

    /**
     * The gateway created the PaymentIntent identified by externalId; the customer can now pay it.
     */
    public Payment markPending(String externalId) {
        requireStatus("mark pending", PaymentStatus.INITIATED);
        return withState(PaymentStatus.PENDING, externalId);
    }

    /**
     * The gateway call creating the PaymentIntent failed, so this attempt can never be paid.
     */
    public Payment fail() {
        requireStatus("fail", PaymentStatus.INITIATED);
        return withState(PaymentStatus.FAILED, externalId);
    }

    /**
     * Applies a status the gateway reported for this payment's PaymentIntent: CONFIRMED once it's
     * paid, ATTEMPT_DECLINED when a payment attempt was declined (the customer may still retry with
     * another payment method). Reporting the status the payment already has is a no-op returning
     * this same payment, since the gateway may deliver the same event more than once. Anything else
     * is rejected - in particular a confirmation arriving after the payment already expired, and any
     * status change of a payment that already reached a terminal status.
     */
    public Payment applyGatewayStatus(PaymentStatus gatewayStatus) {
        if (gatewayStatus == status) {
            return this;
        }

        return switch (gatewayStatus) {
            case CONFIRMED -> {
                requireStatus("confirm", PaymentStatus.PENDING, PaymentStatus.ATTEMPT_DECLINED);
                yield withState(PaymentStatus.CONFIRMED, externalId);
            }
            case ATTEMPT_DECLINED -> {
                requireStatus("decline attempt", PaymentStatus.PENDING);
                yield withState(PaymentStatus.ATTEMPT_DECLINED, externalId);
            }
            default -> throw new DomainException(
                    "Gateway status " + gatewayStatus + " cannot be applied to payment " + id);
        };
    }

    private void requireStatus(String action, PaymentStatus... expected) {
        if (!List.of(expected).contains(status)) {
            throw new DomainException("Cannot " + action + " payment " + id + ": expected status "
                    + List.of(expected) + " but was " + status);
        }
    }

    private Payment withState(PaymentStatus status, String externalId) {
        return new Payment(id, orderId, externalId, amount, status, refundedAmount, version, createdAt, Instant.now(), expiresAt);
    }

    private Payment withRefundedAmount(Money refundedAmount) {
        return new Payment(id, orderId, externalId, amount, status, refundedAmount, version, createdAt, Instant.now(), expiresAt);
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
                + ", status=" + status + ", refundedAmount=" + refundedAmount + ", version=" + version
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + ", expiresAt=" + expiresAt + "}";
    }

    /**
     * A brand-new payment attempt, persisted before the PaymentIntent is created on Stripe.
     */
    public static Payment createNew(long orderId, Money amount) {
        var now = Instant.now();
        return new Payment(0L, orderId, null, amount, PaymentStatus.INITIATED, Money.zero(amount.currency()), 0L,
                now, null, now.plus(EXPIRATION));
    }

    public static Payment createExisting(
            long id,
            long orderId,
            String externalId,
            Money amount,
            PaymentStatus status,
            Money refundedAmount,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {
        if (id <= 0) {
            throw new DomainException("Payment id must be positive");
        }
        return new Payment(id, orderId, externalId, amount, status, refundedAmount, version, createdAt, updatedAt, expiresAt);
    }
}
