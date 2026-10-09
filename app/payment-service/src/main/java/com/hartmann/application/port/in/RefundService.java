package com.hartmann.application.port.in;

import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Money;
import java.util.List;
import java.util.UUID;

public interface RefundService {
    List<Refund> getByOrderId(long orderId);

    /**
     * Creates and submits a refund, idempotently per idempotencyKey (the Kafka messageId): a retried
     * request resumes the refund its key already created instead of creating another one, and a
     * refund that was already submitted is returned as it is. correlationId is propagated to the
     * published events.
     */
    Refund submitRefund(UUID idempotencyKey, String correlationId, long paymentId, Money amount);

    Refund updateStatusByExternalId(String externalId, RefundStatus status);
}
