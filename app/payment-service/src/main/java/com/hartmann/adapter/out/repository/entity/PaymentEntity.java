package com.hartmann.adapter.out.repository.entity;

import com.hartmann.domain.exception.InvalidException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.model.exception.InconsistentPaymentException;
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

    /**
     * A stored payment that breaks one of the payment's own rules is corrupted data, not a request to reject, so it is
     * reported as InconsistentPaymentException. Payment.createExisting already throws that for the payment's own
     * rules; what is caught here is a value built on the way - a negative amount - rejected as invalid, which on its
     * own would read as the caller's fault, or an unknown status or currency valueOf rejected.
     */
    public static Payment toPayment(PaymentEntity entity) {
        try {
            return buildPayment(entity);
        } catch (InvalidException | IllegalArgumentException e) {
            throw new InconsistentPaymentException(entity.id(), e);
        }
    }

    private static Payment buildPayment(PaymentEntity entity) {
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
