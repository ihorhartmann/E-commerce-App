package com.hartmann.application.port.out.repository;

import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;

import java.util.List;

public interface PaymentRepository {
    Payment create(Payment payment);

    Payment getById(long paymentId);

    Payment getByOrderId(long orderId);

    Payment markPending(long paymentId, long version, String externalId);

    Payment updateStatus(long paymentId, long version, PaymentStatus status);

    Payment getByExternalId(String externalId);

    Payment updateRefundedAmount(Payment payment);

    /**
     * Moves every non-terminal payment (INITIATED/PENDING/ATTEMPT_DECLINED) whose expiresAt has
     * passed to EXPIRED, and returns the rows that were changed. A null externalId on a returned
     * row means it never left INITIATED - order-service already resolved that order synchronously
     * when the gateway call failed, so callers must not publish a Kafka event for those.
     */
    List<Payment> expireOverdue();
}
