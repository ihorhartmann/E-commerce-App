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

    /**
     * Stores the payment's refunded amount - a running total, though it could be summed from the refunds - under
     * the payment's version. That version check is what stops concurrent refunds from together exceeding the
     * amount: summed from the refunds, two requests would each see the old balance and both insert their refund,
     * since the refund rows don't conflict with each other. The loser gets a ConcurrentUpdateException and its
     * transaction, the refund included, is rolled back. Write it in the same transaction as the refund it counts.
     */
    Payment updateRefundedAmount(Payment payment);

    /**
     * Every payment still open (INITIATED/PENDING/ATTEMPT_DECLINED) whose expiresAt has passed - the ones the
     * expiration sweep gives up on - oldest first. Only read: each is expired on its own, after its PaymentIntent is
     * cancelled at the gateway.
     */
    List<Payment> findOverdue();
}
