package com.hartmann.domain.model;

import com.hartmann.domain.model.exception.InconsistentPaymentException;
import com.hartmann.domain.model.exception.InvalidPaymentOrderIdException;
import com.hartmann.domain.model.exception.PaymentInvariantViolatedException;
import com.hartmann.domain.model.exception.PaymentNotRefundableException;
import com.hartmann.domain.model.exception.PaymentTransitionNotAllowedException;
import com.hartmann.domain.model.exception.RefundExceedsBalanceException;
import com.hartmann.domain.value.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private String externalId;
    private final Money amount;
    private PaymentStatus status;
    private Money refundedAmount;

    private final long version;
    private final Instant createdAt;
    private Instant updatedAt;
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

    public Refund requestRefund(Money amount, UUID idempotencyKey) {
        if (status != PaymentStatus.CONFIRMED) {
            throw new PaymentNotRefundableException(id, status);
        }
        amount.requireSameCurrency(this.amount);

        var refundableBalance = this.amount.subtract(refundedAmount);
        if (amount.isGreaterThan(refundableBalance)) {
            throw new RefundExceedsBalanceException(id, amount, refundableBalance);
        }

        var refund = Refund.createNew(id, idempotencyKey, null, amount, RefundStatus.INITIATED);
        changeRefundedAmount(refundedAmount.add(amount));
        return refund;
    }

    public void releaseRefund(Refund failedRefund) {
        if (failedRefund.paymentId() != id || failedRefund.status() != RefundStatus.FAILED) {
            throw new PaymentInvariantViolatedException("refund to release must be a failed refund of this payment",
                    Map.of("paymentId", id, "refundId", failedRefund.id()));
        }

        changeRefundedAmount(refundedAmount.subtract(failedRefund.amount()));
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
    public void markPending(String externalId) {
        requireStatus("mark pending", PaymentStatus.INITIATED);
        if (externalId == null || externalId.isBlank()) {
            throw new PaymentInvariantViolatedException("gateway's externalId must not be blank", Map.of("paymentId", id));
        }

        changeState(PaymentStatus.PENDING, externalId);
    }

    /**
     * The gateway call creating the PaymentIntent failed, so this attempt can never be paid.
     */
    public void fail() {
        requireStatus("fail", PaymentStatus.INITIATED);
        changeState(PaymentStatus.FAILED, externalId);
    }

    /**
     * Applies a status the gateway reported for this payment's PaymentIntent: CONFIRMED once it's
     * paid, ATTEMPT_DECLINED when a payment attempt was declined (the customer may still retry with
     * another payment method). Reporting the status the payment already has is a no-op, since the
     * gateway may deliver the same event more than once. Anything else is rejected - in particular a
     * confirmation arriving after the payment already expired, and any status change of a payment
     * that already reached a terminal status.
     *
     * @return whether the status changed, false for a repeated report
     */
    public boolean applyGatewayStatus(PaymentStatus gatewayStatus) {
        if (gatewayStatus == status) {
            return false;
        }

        switch (gatewayStatus) {
            case CONFIRMED -> requireStatus("confirm", PaymentStatus.PENDING, PaymentStatus.ATTEMPT_DECLINED);
            case ATTEMPT_DECLINED -> requireStatus("decline attempt", PaymentStatus.PENDING);
            default -> throw new PaymentInvariantViolatedException("gateway status must be CONFIRMED or ATTEMPT_DECLINED",
                    Map.of("paymentId", id, "gatewayStatus", gatewayStatus));
        }

        changeState(gatewayStatus, externalId);
        return true;
    }

    private void requireStatus(String action, PaymentStatus... expected) {
        if (!List.of(expected).contains(status)) {
            throw new PaymentTransitionNotAllowedException(id, action, status);
        }
    }

    private void changeState(PaymentStatus status, String externalId) {
        this.status = status;
        this.externalId = externalId;
        this.updatedAt = Instant.now();
    }

    private void changeRefundedAmount(Money refundedAmount) {
        this.refundedAmount = refundedAmount;
        this.updatedAt = Instant.now();
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
        // the order id comes with the request; the amount is built from it, so it is never missing
        if (orderId <= 0) {
            throw new InvalidPaymentOrderIdException(orderId);
        }
        if (amount == null) {
            throw new PaymentInvariantViolatedException("amount must not be null", Map.of("orderId", orderId));
        }
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
        var brokenRule = findBrokenRule(id, orderId, externalId, amount, status, refundedAmount, createdAt, expiresAt);
        if (brokenRule != null) {
            throw new InconsistentPaymentException(id, brokenRule);
        }
        return new Payment(id, orderId, externalId, amount, status, refundedAmount, version, createdAt, updatedAt, expiresAt);
    }

    /** The first of the payment's own rules the stored data breaks, or null. */
    private static String findBrokenRule(
            long id,
            long orderId,
            String externalId,
            Money amount,
            PaymentStatus status,
            Money refundedAmount,
            Instant createdAt,
            Instant expiresAt
    ) {
        if (id <= 0) {
            return "id must be positive";
        }
        if (orderId <= 0) {
            return "orderId must be positive";
        }
        if (status == null) {
            return "status must not be null";
        }
        if (!STATUSES_ALLOWING_NO_EXTERNAL_ID.contains(status) && (externalId == null || externalId.isBlank())) {
            return "externalId must not be blank once submitted";
        }
        if (amount == null || refundedAmount == null) {
            return "amount and refundedAmount must not be null";
        }
        if (amount.currency() != refundedAmount.currency()) {
            return "refundedAmount must be in the amount's currency";
        }
        if (refundedAmount.isGreaterThan(amount)) {
            return "refundedAmount must not exceed the amount";
        }
        if (createdAt == null || expiresAt == null) {
            return "createdAt and expiresAt must not be null";
        }
        return null;
    }
}
