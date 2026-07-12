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
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
public class RefundInMemRepository implements RefundRepository {
    private final Map<Long, Refund> refunds = new HashMap<>();
    private final Map<Long, Long> paymentOrderIds = new HashMap<>();

    public RefundInMemRepository() {
        paymentOrderIds.putAll(Map.of(
                1L, 1001L,
                2L, 1002L,
                3L, 1003L,
                4L, 1004L
        ));

        var now = Instant.now();
        List.of(
                new Refund(1L, 1L, new Money(new BigDecimal("100.00"), Currency.EUR), RefundStatus.SUCCEEDED, now, now),
                new Refund(2L, 2L, new Money(new BigDecimal("50.00"), Currency.USD), RefundStatus.SUCCEEDED, now, now),
                new Refund(3L, 2L, new Money(new BigDecimal("75.00"), Currency.USD), RefundStatus.SUCCEEDED, now, now)
        ).forEach(refund -> refunds.put(refund.id(), refund));
    }

    @Override
    public Refund create(Refund refund) {
        refunds.put(refund.id(), refund);
        return refund;
    }

    @Override
    public List<Refund> findByOrderId(long orderId) {
        return refunds.values()
                .stream()
                .filter(refund -> Objects.equals(paymentOrderIds.get(refund.paymentId()), orderId))
                .toList();
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
                existing.amount(),
                status,
                existing.createdAt(),
                Instant.now()
        );
        refunds.put(refundId, updated);
        return updated;
    }
}
