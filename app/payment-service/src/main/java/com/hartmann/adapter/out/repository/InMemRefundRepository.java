package com.hartmann.adapter.out.repository;

import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
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
public class InMemRefundRepository implements RefundRepository {
    private final Map<Long, Refund> refunds = new HashMap<>();
    private final AtomicLong idSequence = new AtomicLong(4);

    public InMemRefundRepository() {
        var now = Instant.now();
        List.of(
                new Refund(1L, 1L, "re_seed_1", new Money(new BigDecimal("100.00"), Currency.EUR), RefundStatus.SUCCEEDED, now, now),
                new Refund(2L, 2L, "re_seed_2", new Money(new BigDecimal("50.00"), Currency.USD), RefundStatus.SUCCEEDED, now, now),
                new Refund(3L, 2L, "re_seed_3", new Money(new BigDecimal("75.00"), Currency.USD), RefundStatus.SUCCEEDED, now, now)
        ).forEach(refund -> refunds.put(refund.id(), refund));
    }

    @Override
    public Refund create(Refund refund) {
        var id = idSequence.getAndIncrement();
        var created = new Refund(
                id,
                refund.paymentId(),
                refund.externalId(), refund.amount(),
                refund.status(),
                refund.createdAt(),
                refund.updatedAt()
        );
        refunds.put(id, created);
        return created;
    }

    @Override
    public List<Refund> findByPaymentId(long paymentId) {
        return refunds.values()
                .stream()
                .filter(refund -> refund.paymentId() == paymentId)
                .toList();
    }

    @Override
    public Refund getByExternalId(String externalId) {
        return refunds.values()
                .stream()
                .filter(refund -> refund.externalId().equals(externalId))
                .findFirst()
                .orElseThrow(() -> new ObjectNotFoundException(
                        "Refund with external id " + externalId + " not found"));
    }

    @Override
    public Refund updateStatus(long refundId, RefundStatus status) {
        var existing = refunds.get(refundId);
        if (existing == null) {
            throw new ObjectNotFoundException("Refund with id " + refundId + " not found");
        }

        var updated = new Refund(
                existing.id(),
                existing.paymentId(),
                existing.externalId(), existing.amount(),
                status,
                existing.createdAt(),
                Instant.now()
        );
        refunds.put(refundId, updated);
        return updated;
    }
}
