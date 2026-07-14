package com.hartmann.adapter.out.repository;

import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentInMemRepository implements PaymentRepository {
    private final Map<Long, Payment> payments = new HashMap<>();
    private final AtomicLong idSequence = new AtomicLong(5);

    public PaymentInMemRepository() {
        var now = Instant.now();
        List.of(
                new Payment(1L, 1001L, "pi_seed_1", new Money(new BigDecimal("100.00"), Currency.EUR), PaymentStatus.COMPLETED, List.of(), now, now),
                new Payment(2L, 1002L, "pi_seed_2", new Money(new BigDecimal("250.00"), Currency.USD), PaymentStatus.COMPLETED, List.of(), now, now),
                new Payment(3L, 1003L, "pi_seed_3", new Money(new BigDecimal("75.00"), Currency.GBP), PaymentStatus.FAILED, List.of(), now, now),
                new Payment(4L, 1004L, "pi_seed_4", new Money(new BigDecimal("45.00"), Currency.EUR), PaymentStatus.PENDING, List.of(), now, now)
        ).forEach(payment -> payments.put(payment.id(), payment));
    }

    @Override
    public Payment create(Payment payment) {
        var id = idSequence.getAndIncrement();
        var created = new Payment(
                id,
                payment.orderId(),
                payment.externalId(), payment.amount(),
                payment.status(),
                payment.refunds(),
                payment.createdAt(),
                payment.updatedAt()
        );
        payments.put(id, created);
        return created;
    }

    @Override
    public Payment getById(long paymentId) {
        var payment = payments.get(paymentId);
        if (payment == null) {
            throw new ObjectNotFoundException("Payment with id " + paymentId + " not found");
        }
        return payment;
    }

    @Override
    public Payment getByOrderId(long orderId) {
        return payments.values()
                .stream()
                .filter(payment -> payment.orderId() == orderId)
                .findFirst()
                .orElseThrow(() -> new ObjectNotFoundException("Payment for order " + orderId + " not found"));
    }

    @Override
    public Payment updateStatus(long paymentId, PaymentStatus status) {
        var existing = payments.get(paymentId);
        if (existing == null) {
            throw new ObjectNotFoundException("Payment with id " + paymentId + " not found");
        }

        var updated = new Payment(
                existing.id(),
                existing.orderId(),
                existing.externalId(), existing.amount(),
                status,
                existing.refunds(),
                existing.createdAt(),
                Instant.now()
        );
        payments.put(paymentId, updated);
        return updated;
    }

    @Override
    public Payment getByExternalId(String externalId) {
        return payments.values()
                .stream()
                .filter(payment -> payment.externalId().equals(externalId))
                .findFirst()
                .orElseThrow(() -> new ObjectNotFoundException(
                        "Payment with external id " + externalId + " not found"));
    }
}
