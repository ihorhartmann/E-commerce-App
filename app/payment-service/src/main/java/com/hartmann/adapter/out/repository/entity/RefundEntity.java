package com.hartmann.adapter.out.repository.entity;

import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

import java.math.BigDecimal;
import java.time.Instant;

// RefundEntity is the refunds table row shape.
public record RefundEntity(
        long id,
        long paymentId,
        String externalId,
        BigDecimal amount,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static RefundEntity from(Refund refund) {
        return new RefundEntity(
                refund.id(),
                refund.paymentId(),
                refund.externalId(),
                refund.amount().amount(),
                refund.amount().currency().name(),
                refund.status().name(),
                refund.createdAt(),
                refund.updatedAt()
        );
    }

    public static Refund toRefund(RefundEntity entity) {
        return Refund.createExisting(
                entity.id(),
                entity.paymentId(),
                entity.externalId(),
                new Money(entity.amount(), Currency.valueOf(entity.currency())),
                RefundStatus.valueOf(entity.status()),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
