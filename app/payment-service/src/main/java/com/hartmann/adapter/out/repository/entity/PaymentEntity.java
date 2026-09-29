package com.hartmann.adapter.out.repository.entity;

import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

import java.math.BigDecimal;
import java.time.Instant;

// PaymentEntity is the payments table row shape.
public record PaymentEntity(
        long id,
        long orderId,
        String externalId,
        BigDecimal amount,
        String currency,
        String status,
        BigDecimal refundedAmount,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt
) {
    public static PaymentEntity from(Payment payment) {
        return new PaymentEntity(
                payment.id(),
                payment.orderId(),
                payment.externalId(),
                payment.amount().amount(),
                payment.amount().currency().name(),
                payment.status().name(),
                payment.refundedAmount().amount(),
                payment.version(),
                payment.createdAt(),
                payment.updatedAt(),
                payment.expiresAt()
        );
    }

    public static Payment toPayment(PaymentEntity entity) {
        var currency = Currency.valueOf(entity.currency());
        return Payment.createExisting(
                entity.id(),
                entity.orderId(),
                entity.externalId(),
                new Money(entity.amount(), currency),
                PaymentStatus.valueOf(entity.status()),
                new Money(entity.refundedAmount(), currency),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt(),
                entity.expiresAt()
        );
    }
}
