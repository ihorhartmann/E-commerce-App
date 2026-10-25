package com.hartmann.adapter.out.repository.entity;

import com.hartmann.domain.exception.InvalidException;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.model.exception.InconsistentRefundException;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// RefundEntity is the refunds table row shape.
public record RefundEntity(
        long id,
        long paymentId,
        UUID idempotencyKey,
        String externalId,
        BigDecimal amount,
        String currency,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static RefundEntity from(Refund refund) {
        return new RefundEntity(
                refund.id(),
                refund.paymentId(),
                refund.idempotencyKey(),
                refund.externalId(),
                refund.amount().amount(),
                refund.amount().currency().name(),
                refund.status().name(),
                refund.version(),
                refund.createdAt(),
                refund.updatedAt()
        );
    }

    /**
     * A stored refund that breaks one of the refund's own rules is corrupted data, not a request to reject, so it is
     * reported as InconsistentRefundException. Refund.createExisting already throws that for the refund's own rules;
     * what is caught here is a value built on the way - a negative amount - rejected as invalid, which on its own
     * would read as the caller's fault, or an unknown status or currency valueOf rejected.
     */
    public static Refund toRefund(RefundEntity entity) {
        try {
            return buildRefund(entity);
        } catch (InvalidException | IllegalArgumentException e) {
            throw new InconsistentRefundException(entity.id(), e);
        }
    }

    private static Refund buildRefund(RefundEntity entity) {
        return Refund.createExisting(
                entity.id(),
                entity.paymentId(),
                entity.idempotencyKey(),
                entity.externalId(),
                new Money(entity.amount(), Currency.valueOf(entity.currency())),
                RefundStatus.valueOf(entity.status()),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
