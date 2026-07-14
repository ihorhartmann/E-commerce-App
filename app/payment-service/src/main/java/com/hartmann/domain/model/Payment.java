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
