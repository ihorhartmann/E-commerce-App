package com.hartmann.adapter.out.db;

import com.hartmann.application.port.out.db.PaymentRepository;
import com.hartmann.application.port.out.db.exception.ObjectNotFoundException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentInMemRepository implements PaymentRepository {
    private final Map<Long, Payment> payments = new HashMap<>();

    public PaymentInMemRepository() {
        var now = Instant.now();
        List.of(
                new Payment(1L, 1001L, new Money(100.0, Currency.EUR), PaymentStatus.COMPLETED, now, now),
                new Payment(2L, 1002L, new Money(250.0, Currency.USD), PaymentStatus.COMPLETED, now, now),
                new Payment(3L, 1003L, new Money(75.0, Currency.GBP), PaymentStatus.FAILED, now, now),
                new Payment(4L, 1004L, new Money(40.0, Currency.EUR), PaymentStatus.PENDING, now, now)
        ).forEach(payment -> payments.put(payment.id(), payment));
    }

    @Override
    public Payment create(Payment payment) {
        payments.put(payment.id(), payment);
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
                existing.amount(),
                status,
                existing.createdAt(),
                Instant.now()
        );
        payments.put(paymentId, updated);
        return updated;
    }
}
