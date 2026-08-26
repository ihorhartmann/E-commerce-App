package com.hartmann.adapter.out.repository.entity;

import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// PaymentEntity is the payments table row shape.
public record PaymentEntity(
        long id,
        long orderId,
        String externalId,
        BigDecimal amount,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentEntity from(Payment payment) {
        return new PaymentEntity(
                payment.id(),
                payment.orderId(),
                payment.externalId(),
                payment.amount().amount(),
                payment.amount().currency().name(),
                payment.status().name(),
                payment.createdAt(),
                payment.updatedAt()
        );
    }

    public static Payment toPayment(PaymentEntity entity) {
        return toPayment(entity, List.of());
    }

    public static Payment toPayment(PaymentEntity entity, List<com.hartmann.domain.model.Refund> refunds) {
        return Payment.createExisting(
                entity.id(),
                entity.orderId(),
                entity.externalId(),
                new Money(entity.amount(), Currency.valueOf(entity.currency())),
                PaymentStatus.valueOf(entity.status()),
                refunds,
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
