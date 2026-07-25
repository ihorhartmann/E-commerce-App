package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Money;

import java.time.Instant;
import java.util.List;

public record Payment(
        long id,
        long orderId,
        String externalId,
        Money amount,
        PaymentStatus status,
        List<Refund> refunds,
        Instant createdAt,
        Instant updatedAt
) {
    public Payment {
        if (id < 0) {
            throw new DomainException("Payment id must not be negative");
        }
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
        if (createdAt == null || updatedAt == null) {
            throw new DomainException("Payment createdAt/updatedAt must not be null");
        }
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

        var now = Instant.now();
        return new Refund(0L, id, null, amount, RefundStatus.PENDING, now, now);
    }
}
